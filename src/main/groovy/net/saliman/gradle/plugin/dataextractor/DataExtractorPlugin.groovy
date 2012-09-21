package net.saliman.gradle.plugin.dataextractor

import org.gradle.api.Project
import org.gradle.api.Plugin


class DataExtractorPlugin implements Plugin<Project> {

	void apply(Project project) {
		project.task('extractData', type: ExtractDataTask)
		project.tasks.extractData.description = "Extracts data from a database to a SQL file."
	}

}
