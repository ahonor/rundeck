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
// Wave 5 migration: execution_confirmation table for the confirm plugin
// audit trail. See docs/specs/confirm-workflow-step.md §7.
//
databaseChangeLog = {

    changeSet(author: "rundeck", id: "6.0-confirm-0100-create-table") {
        preConditions(onFail: "MARK_RAN") {
            not { tableExists(tableName: "execution_confirmation") }
        }
        createTable(tableName: "execution_confirmation") {
            column(name: "id", type: '${number.type}', autoIncrement: true) {
                constraints(primaryKey: true, nullable: false)
            }
            column(name: "execution_id", type: '${number.type}') {
                constraints(nullable: false)
            }
            column(name: "confirmed_by", type: '${varchar255.type}') {
                constraints(nullable: true)
            }
            column(name: "confirmer_roles", type: '${varchar1024.type}') {
                constraints(nullable: true)
            }
            column(name: "decision", type: '${varchar64.type}') {
                constraints(nullable: true)
            }
            column(name: "comment", type: '${text.type}') {
                constraints(nullable: true)
            }
            column(name: "confirmed_at", type: '${timestamp.type}') {
                constraints(nullable: false)
            }
            column(name: "timeout", type: '${boolean.type}', defaultValueBoolean: false) {
                constraints(nullable: false)
            }
            column(name: "step_context", type: '${varchar64.type}') {
                constraints(nullable: false)
            }
        }
    }

    changeSet(author: "rundeck", id: "6.0-confirm-0200-execution-id-index") {
        preConditions(onFail: "MARK_RAN") {
            not { indexExists(tableName: "execution_confirmation", indexName: "IDX_EXEC_CONFIRMATION_EXEC_ID") }
        }
        createIndex(indexName: "IDX_EXEC_CONFIRMATION_EXEC_ID", tableName: "execution_confirmation") {
            column(name: "execution_id")
        }
    }
}
