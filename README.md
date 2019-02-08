Introduction
------------

This plugin is designed to extract data from a test database to a SQL file.
Projects need good, stable, known data for testing, but sometimes the best way
to generate good data is with the application itself.  The challenge is to get
the data out of a running system, where things change, to a SQL script, where
they don't.

This plugin is very much a work in progress at the moment, and I'm not sure
where I will go with it.  For now, it only works in MySQL, and it generates
delete statements before it generates insert statements.  We don't worry about
constraint issues.  At the moment, it is up to the user to supply a list of
tables in the right order.

Usage
-----

I haven't published this to maven central yet, so if you want to use this
plugin, you'll have to download the source, do a "gradle install" to install
it you your local maven repo, then add the following to your build.gradle file.

```groovy
buildscript {
	repositories {
		mavenLocal()
	}
	dependencies {
		classpath "net.saliman:gradle-data-extractor-plugin:1.2.0"
	}
}
apply plugin: 'data-extractor'

configurations {
	extractor
}
dependencies {
	extractor: "org.mariadb.jdbc:mariadb-java-client:2.4.0"
}

extractData {
	// configuration options...
	doFirst() {
		configurations.extractor.each { file ->
			gradle.class.classLoader.addURL(file.toURI().toURL())
		}
	}
}
```

See http://gradle.1045684.n5.nabble.com/using-jdbc-driver-in-a-task-fails-td1435189.html
for the reason we need the doFirst block.  The name of the configuration used is
not important, it just needs to match what we're using in the doFirst block.

There are a few configuration options that can be specified in the extractData
task:

- jdbcDriver: The jdbc driver class name.

- jdbcUrl: the jdbc URL for the database connection.

- jdbcUsername: the username to use.

- jdbcPassword: the password to use.

- sqlFilename: the name (and directory) of the file to generate.  If the file
already exists, it will be overwritten.  The default is "data.sql" in the 
working directory.

- tables: a list of metadata for the tables whose data we want.  The order of
the tables is important.  It needs to be specified in the order in which data
must be deleted to avoid foreign key constraint errors.  The metadata data can
either be a table name, or a map of options for the table.  Supported options
currently include:
	1. name: The name of the table. This is the only required value in the map.
	2. skipColumns: If present, the plugin will omit data for these columns.
	   the data must be an array of lower case column names, e.g.
	   ```skipColumns: [ 'a', 'b' ]``` not ```skipColumns: 'a, b'```
	3. orderBy: The columns by which we order the insert statements.
	4. startSequenceNum: If present a statement will be added before the insert
	   statements to reset the auto increment value for the table's ids to the
	   given number. This will have no impact on tables that don't have the auto
	   incremented column in the ```skipColumns``` property.
	5. endSequenceNum: If present, a statement will be added after the table's
	   insert statements to bump the auto increment sequence to the given
	   number.

Example:
```
tables = [
  'some_table',
  [ name: 'second_table', skipColumns: ['id'], startSequenceNum: 1 ],
  [ name: 'third_table', orderBy: 'last_name, first_name' ]
]
```
