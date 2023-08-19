package `in`.specmatic.test.reports.coverage

import `in`.specmatic.test.API
import `in`.specmatic.test.TestResultRecord

class OpenApiCoverageReportInput(
    val testResultRecords: MutableList<TestResultRecord> = mutableListOf(),
    val applicationAPIs: MutableList<API> = mutableListOf(),
    val excludedAPIs: MutableList<String> = mutableListOf()
) {
    fun addTestReportRecords(testResultRecord: TestResultRecord) {
        testResultRecords.add(testResultRecord)
    }

    fun addAPIs(apis: List<API>) {
        applicationAPIs.addAll(apis)
    }

    fun addExcludedAPIs(apis: List<String>){
        excludedAPIs.addAll(apis)
    }
}