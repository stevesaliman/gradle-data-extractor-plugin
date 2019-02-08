package net.saliman.gradle.plugin.dataextractor

import groovy.sql.Sql
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.gradle.tooling.BuildException

/**
 * Gradle task to extract SQL.
 *
 * @author Steven C. Saliman
 */
class ExtractDataTask extends DefaultTask {
	String jdbcDriver
	String jdbcUrl
	String jdbcUsername
	String jdbcPassword
	// Tables is a collection of strings and maps.
	def tables
	String sqlFilename = "data.sql"
	// Include commit statements?  set to false to generate scripts for LiquiBase
	def includeCommit = true;
	// Include statement to disable foreign keys? Set to true for subsets of data.
	// such as regenerating static-data scripts.
	def disableForeignKeys = false;

	/**
	 * Extract data to a SQL file.
	 */
	@TaskAction
	def extractData() {
		project.logger.info("Extracting data from ${jdbcUrl}")
		if ( tables == null || tables.size() < 1 ) {
			logger.warn("No tables specified. Skipping extraction.")
			return
		}
		// Make sure the driver can be loaded before we try to do anything, then
		// get our database connection.
		Class.forName(jdbcDriver)
		def connection = Sql.newInstance(jdbcUrl, jdbcUsername, jdbcPassword, jdbcDriver)

		// At this point, we're good to go, so we can create (overwrite) the output
		// file.
		def sqlFile = new File(sqlFilename)
		if ( sqlFile.exists() ) {
			sqlFile.delete()
		}
		sqlFile.append("-- This script was generated by the Data Extractor plugin on:\n-- ${new Date()}\n\n")
		project.logger.info("Processing delete statements for ${tables.size()} tables")
		// disable foreign keys.
		if ( disableForeignKeys ) {
			sqlFile.append("set foreign_key_checks = 0;\n\n")
		}
		tables.each { table ->
			processDelete(table, sqlFile)
		}
		if ( includeCommit ) {
			sqlFile.append("commit;\n\n")
		}

		project.logger.info("Processing table data...")
		tables.reverse(false).each { table ->
			// create map with just defaults.
			def tableMap = [:]
			// If we're dealing with a map, extract variables from it.
			if ( table instanceof String ) {
				tableMap.name = table
			} else {
				tableMap << table
			}
			if ( tableMap.name == null ) {
				throw new BuildException("Unable to determine table name from ${table}", null)
			}
			project.logger.info("  processing ${tableMap.name}:")
			sqlFile.append("\n-- Data for the ${tableMap.name} table\n")
			addStartSequenceSql(tableMap, sqlFile)
			addInsertSql(tableMap, sqlFile, connection)
			addEndSequenceSql(tableMap, sqlFile)
		}
		// Re-enable foreign keys.
		if ( disableForeignKeys ) {
			sqlFile.append("set foreign_key_checks = 1;\n\n")
		}

	}

	/**
	 * Helper method to create the Delete SQL for the file.
	 * @param table the metadata for the file with the table name
	 * @param file the file receiving the SQL
	 */
	def processDelete(table, file) {
		def tableName = null
		if ( table instanceof String ) {
			tableName = table
		} else  {
			tableName = table.name
		}
		if ( tableName == null ) {
			throw new BuildException("Unable to determine table name from ${table}", null)
		}
		file.append("delete from ${tableName};\n\n")
	}

	/**
	 * Helper method to add SQL to reset the auto_increment sequence before the
	 * insert statements if the table map has a startSequenceNum.
	 * @param table the table metadata
	 * @param file the SQL file
	 */
	def addStartSequenceSql(table, file) {
		if ( !table.containsKey('startSequenceNum') ) {
			return
		}
		project.logger.info("    Resetting auto increment to ${table.startSequenceNum}")
		file.append("alter table ${table.name} auto_increment=${table.startSequenceNum};\n\n")
	}

	/**
	 * Helper method to add SQL to bump the auto_increment sequence after the
	 * inserts are complete if the table map has a endSequenceNum.
	 * @param table the table metadata
	 * @param file the SQL file
	 */
	def addEndSequenceSql(table, file) {
		if ( !table.containsKey('endSequenceNum') ) {
			return
		}
		project.logger.info("    Bumping auto increment to ${table.endSequenceNum}")
		file.append("alter table ${table.name} auto_increment=${table.endSequenceNum};\n\n")
	}

	/**
	 * Helper method that adds all the insert statements with the table's data.
	 * @param table the table metadata with the name of the table skipped cols, etc.
	 * @param file the file where the SQL goes.
	 * @param connection the database connection.
	 */
	def addInsertSql(table, file, connection) {
		def rowsProcessed = 0
		def colNames = []
		def columns = []
		def db = dbFromJdbcUrl(jdbcUrl)
		def colSql = "select lower(column_name) as column_name, data_type from information_schema.columns " +
						"where table_schema = '${db}' " +
						"and table_name = '${table.name}' " +
						"order by ordinal_position"
		connection.eachRow(colSql) { heading ->
			columns << [name: heading.column_name, type: heading.data_type]
			colNames << heading.column_name
		}
		// Remove columns we're skipping
		if ( table.containsKey('skipColumns') ) {
			project.logger.info("    Skipping ${table.name} columns ${table.skipColumns}")
		  colNames = colNames - table.skipColumns
		}
		def insertText = "insert into ${table.name}(" + colNames.join(",") + ")\n"
		def selectSql = "select " + colNames.join(",") + " from ${table.name}"
		if ( table.containsKey('orderBy') ) {
			project.logger.info("    Ordering ${table.name} data by ${table.orderBy}")
			selectSql = selectSql + " order by ${table.orderBy}"
		}
		connection.eachRow(selectSql) { row ->
			def data = []
			// Iterate over the columns whose names we're not skipping.
			columns.findAll { colNames.contains(it.name)}.each { column ->
				// If we don't have data, use "null", otherwise use the data for the
				// column.  Some column types need to wrap the value in single quotes.
				if ( row[column.name] == null ) {
					data << "null"
				} else  if ( column.type ==~ /.*int/ ) {
					data << row[column.name]
				} else if ( column.type == "decimal" || column.type == "double" ) {
					data << row[column.name]
				} else if ( column.type == "bit" ) {
					data << row[column.name]
				} else {
					data << "'${row[column.name]}'"
				}
			}
			file.append(insertText + "values(" + data.join(",") + ");\n\n", 'UTF-8')
			rowsProcessed++
		}
		if ( includeCommit ) {
			file.append("commit;\n\n")
		}
		project.logger.info("    ${rowsProcessed} rows extracted")

	}
	/**
	 * Helper method to get the database name from a MySql URL, because we need
	 * the database name to get the table metadata.
	 */
	def dbFromJdbcUrl(url) {
		if ( !url.startsWith("jdbc:") ) {
			throw new IllegalArgumentException("Jdbc urls must start with 'jdbc:'")
		}
		// Strip off the "jdbc:" toget a valid URI.
		def newUrl = url.substring(5)
		URI u = new URI(newUrl)
		// Substring to get rid of the leading "/"
		def db = u.getPath().substring(1)
		return db
	}
}
