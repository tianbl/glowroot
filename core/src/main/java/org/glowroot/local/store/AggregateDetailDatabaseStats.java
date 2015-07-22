/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.local.store;

public class AggregateDetailDatabaseStats implements AggregateDetailDatabaseStatsMXBean {

    static final String AGGREGATE_QUERIES = "aggregate queries";
    static final String AGGREGATE_PROFILES = "aggregate profiles";

    private final CappedDatabase cappedDatabase;

    AggregateDetailDatabaseStats(CappedDatabase cappedDatabase) {
        this.cappedDatabase = cappedDatabase;
    }

    @Override
    public CappedDatabaseStats getAggregateQueries() {
        return cappedDatabase.getStats(AGGREGATE_QUERIES);
    }

    @Override
    public CappedDatabaseStats getAggregateProfiles() {
        return cappedDatabase.getStats(AGGREGATE_PROFILES);
    }
}
