/*
 * Copyright 2025-2026 Johannes Zemlin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package ai.koryki.measure.catalog;

/**
 * A table a measure reads columns of, besides its own: reached from the measure's table by a link
 * of the model that leads to <em>one</em> row of it ({@code emp} to its {@code dept}). The
 * expression of the measure names its columns {@code alias.column}.
 *
 * <p>Plain data. Because every row of the measure's table meets at most one row of the joined one,
 * the rows the measure counts are still the rows of its own table, each once.
 */
public class MeasureJoin {

    private String alias;
    private String entity;
    private String via;

    public MeasureJoin() {}

    public MeasureJoin(String alias, String entity, String via) {
        this.alias = alias;
        this.entity = entity;
        this.via = via;
    }

    /** The name the expression of the measure uses for the joined table. */
    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    /** The entity of the model that is joined. */
    public String getEntity() {
        return entity;
    }

    public void setEntity(String entity) {
        this.entity = entity;
    }

    /** The link of the model, from the entity of the measure's table, that leads to it. */
    public String getVia() {
        return via;
    }

    public void setVia(String via) {
        this.via = via;
    }
}
