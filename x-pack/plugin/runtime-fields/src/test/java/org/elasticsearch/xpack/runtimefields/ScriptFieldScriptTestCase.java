/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.runtimefields;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.RandomIndexWriter;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Scorable;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.store.Directory;
import org.elasticsearch.common.CheckedConsumer;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.fielddata.IndexFieldData;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.indices.breaker.NoneCircuitBreakerService;
import org.elasticsearch.painless.PainlessPlugin;
import org.elasticsearch.plugins.ExtensiblePlugin.ExtensionLoader;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptContext;
import org.elasticsearch.script.ScriptModule;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.search.lookup.SearchLookup;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntFunction;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public abstract class ScriptFieldScriptTestCase<F, LF, R> extends ESTestCase {
    protected abstract ScriptContext<F> scriptContext();

    protected abstract LF newLeafFactory(F factory, Map<String, Object> params, SearchLookup searchLookup);

    protected abstract IntFunction<List<R>> newInstance(LF leafFactory, LeafReaderContext context) throws IOException;

    protected final List<R> execute(CheckedConsumer<RandomIndexWriter, IOException> indexBuilder, String script, MappedFieldType... types)
        throws IOException {

        PainlessPlugin painlessPlugin = new PainlessPlugin();
        painlessPlugin.loadExtensions(new ExtensionLoader() {
            @Override
            @SuppressWarnings("unchecked") // We only ever load painless extensions here so it is fairly safe.
            public <T> List<T> loadExtensions(Class<T> extensionPointType) {
                return (List<T>) List.of(new RuntimeFieldsPainlessExtension());
            }
        });
        ScriptModule scriptModule = new ScriptModule(Settings.EMPTY, List.of(painlessPlugin, new RuntimeFields()));
        Map<String, Object> params = new HashMap<>();
        MapperService mapperService = mock(MapperService.class);
        for (MappedFieldType type : types) {
            when(mapperService.fieldType(type.name())).thenReturn(type);
        }
        Function<MappedFieldType, IndexFieldData<?>> fieldDataLookup = ft -> ft.fielddataBuilder("test")
            .build(null, new NoneCircuitBreakerService(), mapperService);
        SearchLookup searchLookup = new SearchLookup(mapperService, fieldDataLookup);
        try (ScriptService scriptService = new ScriptService(Settings.EMPTY, scriptModule.engines, scriptModule.contexts)) {
            F factory = AccessController.doPrivileged(
                (PrivilegedAction<F>) () -> scriptService.compile(new Script(script), scriptContext())
            );

            try (Directory directory = newDirectory(); RandomIndexWriter indexWriter = new RandomIndexWriter(random(), directory)) {
                indexBuilder.accept(indexWriter);
                try (DirectoryReader reader = indexWriter.getReader()) {
                    IndexSearcher searcher = newSearcher(reader);
                    LF leafFactory = newLeafFactory(factory, params, searchLookup);
                    List<R> result = new ArrayList<>();
                    searcher.search(new MatchAllDocsQuery(), new Collector() {
                        @Override
                        public ScoreMode scoreMode() {
                            return ScoreMode.COMPLETE_NO_SCORES;
                        }

                        @Override
                        public LeafCollector getLeafCollector(LeafReaderContext context) throws IOException {
                            IntFunction<List<R>> compiled = newInstance(leafFactory, context);
                            return new LeafCollector() {
                                @Override
                                public void setScorer(Scorable scorer) {}

                                @Override
                                public void collect(int docId) {
                                    result.addAll(compiled.apply(docId));
                                }
                            };
                        }
                    });
                    return result;
                }
            }
        }
    }
}