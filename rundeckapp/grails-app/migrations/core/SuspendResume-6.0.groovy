/*
 * Copyright 2026 Rundeck, Inc. (http://rundeck.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
//
// Wave 2 schema additions for cycle/workflow-suspend-resume. See
// docs/specs/workflow-suspend-resume.md §2.3 and §7.1 for the authoritative
// column definitions and docs/cycles/workflow-suspend-resume.md Wave 2 for
// migration-plan context.
//
// Adds 9 nullable or default-valued columns to the `execution` table so
// existing executions continue to function unchanged while new executions
// can persist suspend-resume state.
//
databaseChangeLog = {

    changeSet(author: "rundeck", id: "6.0-suspend-resume-0100-checkpoint-data") {
        preConditions(onFail: "MARK_RAN") {
            not { columnExists(tableName: "execution", columnName: "checkpoint_data") }
        }
        addColumn(tableName: "execution") {
            column(name: "checkpoint_data", type: '${text.type}') {
                constraints(nullable: "true")
            }
        }
    }

    changeSet(author: "rundeck", id: "6.0-suspend-resume-0110-suspend-metadata") {
        preConditions(onFail: "MARK_RAN") {
            not { columnExists(tableName: "execution", columnName: "suspend_metadata") }
        }
        addColumn(tableName: "execution") {
            column(name: "suspend_metadata", type: '${text.type}') {
                constraints(nullable: "true")
            }
        }
    }

    changeSet(author: "rundeck", id: "6.0-suspend-resume-0120-wait-started-at") {
        preConditions(onFail: "MARK_RAN") {
            not { columnExists(tableName: "execution", columnName: "wait_started_at") }
        }
        addColumn(tableName: "execution") {
            column(name: "wait_started_at", type: '${timestamp.type}') {
                constraints(nullable: "true")
            }
        }
    }

    changeSet(author: "rundeck", id: "6.0-suspend-resume-0130-wait-timeout-at") {
        preConditions(onFail: "MARK_RAN") {
            not { columnExists(tableName: "execution", columnName: "wait_timeout_at") }
        }
        addColumn(tableName: "execution") {
            column(name: "wait_timeout_at", type: '${timestamp.type}') {
                constraints(nullable: "true")
            }
        }
    }

    changeSet(author: "rundeck", id: "6.0-suspend-resume-0140-last-resumed-at") {
        preConditions(onFail: "MARK_RAN") {
            not { columnExists(tableName: "execution", columnName: "last_resumed_at") }
        }
        addColumn(tableName: "execution") {
            column(name: "last_resumed_at", type: '${timestamp.type}') {
                constraints(nullable: "true")
            }
        }
    }

    changeSet(author: "rundeck", id: "6.0-suspend-resume-0150-resume-ready") {
        preConditions(onFail: "MARK_RAN") {
            not { columnExists(tableName: "execution", columnName: "resume_ready") }
        }
        addColumn(tableName: "execution") {
            column(name: "resume_ready", type: '${boolean.type}', defaultValueBoolean: false) {
                constraints(nullable: "false")
            }
        }
    }

    changeSet(author: "rundeck", id: "6.0-suspend-resume-0160-resume-payload") {
        preConditions(onFail: "MARK_RAN") {
            not { columnExists(tableName: "execution", columnName: "resume_payload") }
        }
        addColumn(tableName: "execution") {
            column(name: "resume_payload", type: '${text.type}') {
                constraints(nullable: "true")
            }
        }
    }

    changeSet(author: "rundeck", id: "6.0-suspend-resume-0170-resume-attempt-count") {
        preConditions(onFail: "MARK_RAN") {
            not { columnExists(tableName: "execution", columnName: "resume_attempt_count") }
        }
        addColumn(tableName: "execution") {
            column(name: "resume_attempt_count", type: '${int.type}', defaultValueNumeric: 0) {
                constraints(nullable: "false")
            }
        }
    }

    changeSet(author: "rundeck", id: "6.0-suspend-resume-0180-pause-requested") {
        preConditions(onFail: "MARK_RAN") {
            not { columnExists(tableName: "execution", columnName: "pause_requested") }
        }
        addColumn(tableName: "execution") {
            column(name: "pause_requested", type: '${boolean.type}', defaultValueBoolean: false) {
                constraints(nullable: "false")
            }
        }
    }

    changeSet(author: "rundeck", id: "6.0-suspend-resume-0200-claim-index") {
        preConditions(onFail: "MARK_RAN") {
            not { indexExists(tableName: "execution", indexName: "EXECUTION_WAITING_CLAIM_IDX") }
        }
        createIndex(indexName: "EXECUTION_WAITING_CLAIM_IDX", tableName: "execution") {
            column(name: "status")
            column(name: "resume_ready")
            column(name: "server_node_uuid")
        }
    }

    changeSet(author: "rundeck", id: "6.0-suspend-resume-0210-timeout-index") {
        preConditions(onFail: "MARK_RAN") {
            not { indexExists(tableName: "execution", indexName: "EXECUTION_WAIT_TIMEOUT_IDX") }
        }
        createIndex(indexName: "EXECUTION_WAIT_TIMEOUT_IDX", tableName: "execution") {
            column(name: "status")
            column(name: "wait_timeout_at")
        }
    }
}
