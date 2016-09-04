/**
 * 
 */
package querqy.solr;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.util.ToStringUtils;
import org.apache.solr.core.AbstractSolrEventListener;
import org.apache.solr.core.SolrCore;
import org.apache.solr.search.SolrCache;
import org.apache.solr.search.SolrIndexSearcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import querqy.lucene.rewrite.DependentTermQuery;
import querqy.lucene.rewrite.DocumentFrequencyCorrection;
import querqy.lucene.rewrite.FieldBoost;
import querqy.lucene.rewrite.NeverMatchQueryFactory;
import querqy.lucene.rewrite.TermQueryFactory;
import querqy.lucene.rewrite.TermSubQueryBuilder;
import querqy.lucene.rewrite.TermSubQueryFactory;
import querqy.lucene.rewrite.cache.CacheKey;
import querqy.lucene.rewrite.cache.TermQueryCache;
import querqy.lucene.rewrite.cache.TermQueryCacheValue;
import querqy.lucene.rewrite.prms.PRMSQuery;
import querqy.model.Term;
import querqy.rewrite.RewriteChain;
import querqy.rewrite.RewriterFactory;

/**
 * @author rene
 *
 */
public class TermQueryCachePreloader extends AbstractSolrEventListener {
    
    static final Logger LOG = LoggerFactory.getLogger(TermQueryCachePreloader.class); 
    
    public static final String CONF_Q_PARSER_PLUGIN = "qParserPlugin";
    
    public static final String CONF_PRELOAD_FIELDS = "fields";
    
    public static final String CONF_CACHE_NAME = "cacheName";
    
    public static final String CONF_TEST_FOR_HITS = "testForHits";
    
    static final FieldBoost DUMMY_FIELD_BOOST = new FieldBoost() {
        @Override
        public void registerTermSubQuery(String fieldname,
                                         TermSubQueryFactory termSubQueryFactory, Term sourceTerm) {
        }
        @Override
        public float getBoost(String fieldname, IndexSearcher searcher)
                throws IOException {
            return 1f;
        }
        @Override
        public String toString(String fieldname) {
            return ToStringUtils.boost(1f);
        }
    };

    public TermQueryCachePreloader(SolrCore core) {
        super(core);
    }
    
    protected Map<String, Float> getPreloadFields() {
        // REVISIT: we don't need the boost factors as could will be overridden per request
       String fieldConf = (String) getArgs().get(CONF_PRELOAD_FIELDS); 
       return QuerqyDismaxQParser.parseFieldBoosts(fieldConf, 1f);
    }
    
    protected AbstractQuerqyDismaxQParserPlugin getQParserPlugin() {
        String parserName = (String) getArgs().get(CONF_Q_PARSER_PLUGIN); 
        if (parserName == null) {
            throw new RuntimeException("Missing configuration property: " + CONF_Q_PARSER_PLUGIN);
        }
        AbstractQuerqyDismaxQParserPlugin qParserPlugin = (AbstractQuerqyDismaxQParserPlugin) getCore().getQueryPlugin(parserName);
        if (qParserPlugin == null) {
            throw new RuntimeException("No query parser plugin for name '" + parserName + "'");
        }
        return qParserPlugin;
    }
    
    protected TermQueryCache getCache(SolrIndexSearcher searcher) {
        String cacheName = (String) getArgs().get(CONF_CACHE_NAME); 
        if (cacheName == null) {
            throw new RuntimeException("Missing configuration property: " + CONF_CACHE_NAME);
        }
        
        @SuppressWarnings("unchecked")
        SolrCache<CacheKey, TermQueryCacheValue> solrCache = searcher.getCache(cacheName);
        if (solrCache == null) {
            throw new RuntimeException("No TermQueryCache for name '" + cacheName + "'");
        }
        
        return new SolrTermQueryCacheAdapter(false, solrCache);
    }
    
    protected boolean isTestForHits() {
        Boolean doTest = getArgs().getBooleanArg(CONF_TEST_FOR_HITS);
        return doTest != null && doTest;
    }
    
    @Override
    public void newSearcher(SolrIndexSearcher newSearcher, SolrIndexSearcher currentSearcher) {
        
        
        TermQueryCache cache = getCache(newSearcher);
        
        Map<String, Float> preloadFields = getPreloadFields();
        
        boolean testForHits = isTestForHits();
        
        LOG.info("Starting preload for Querqy TermQueryCache. Testing for hits: {}", testForHits);
        long t1 = System.currentTimeMillis();
        
        
        AbstractQuerqyDismaxQParserPlugin queryPluginPlugin = getQParserPlugin();
        RewriteChain rewriteChain = queryPluginPlugin.getRewriteChain();
        
        if (rewriteChain != null && !preloadFields.isEmpty()) {
        
            List<RewriterFactory> factories = rewriteChain.getRewriterFactories();
            if (!factories.isEmpty()) {
            
                TermSubQueryBuilder termSubQueryBuilder = new PrelaodTermSubQueryBuilder(newSearcher.getSchema().getQueryAnalyzer(), cache);
                for (RewriterFactory factory : factories) {
                    for (Term term: factory.getGenerableTerms()) {
                        String field = term.getField();
                        if (field != null) {
                            if (preloadFields.containsKey(field)) {
                                preloadTerm(newSearcher, termSubQueryBuilder, field, term, testForHits, cache);
                            }
                        } else {
                            for (String fieldname : preloadFields.keySet()) {
                                preloadTerm(newSearcher, termSubQueryBuilder, fieldname, term, testForHits, cache);
                            }
                        }
                    }
                }
                
            }
            
        }
        
        if (LOG.isInfoEnabled()) {
            long t2 = System.currentTimeMillis();
            LOG.info("Finished preload for Querqy TermQueryCache after {}ms", (t2 - t1));
        }
        
    }
    

    protected void preloadTerm(IndexSearcher searcher, TermSubQueryBuilder termSubQueryBuilder, String field, Term term, boolean testForHits, TermQueryCache cache) {
        
        try {
            
            // luceneQueryBuilder.termToFactory creates the query and caches it (without the boost)
            TermSubQueryFactory termSubQueryFactory = termSubQueryBuilder.termToFactory(field, term, DUMMY_FIELD_BOOST);
            
            // test the query for hits and override the cache value with a factory that creates a query that never matches
            // --> this query will never be executed against the index again
            
            // no need to re-test for hits if we've seen this term before
            if (testForHits && (termSubQueryFactory != null) && (!termSubQueryFactory.isNeverMatchQuery())) {
                Query query = termSubQueryFactory.createQuery(DUMMY_FIELD_BOOST, 0.01f, null, false);
                TopDocs topDocs = searcher.search(query, 1);
                if (topDocs.totalHits < 1) {
                    cache.put(new CacheKey(field, term), new TermQueryCacheValue(NeverMatchQueryFactory.FACTORY, PRMSQuery.NEVER_MATCH_PRMS_QUERY));
                }
            }
        
        } catch (IOException e) {
            LOG.error("Error preloading term " + term.toString(), e);
        }
    }
    
    class PrelaodTermSubQueryBuilder extends TermSubQueryBuilder {
        public PrelaodTermSubQueryBuilder(Analyzer analyzer,
                TermQueryCache termQueryCache) {
            super(analyzer, termQueryCache);
        }

        @Override
        protected TermQueryFactory createTermQueryFactory(
                org.apache.lucene.index.Term term) {
            return new PreloadTermQueryFactory(term);
        }
    }
    
    class PreloadTermQueryFactory extends TermQueryFactory {

        public PreloadTermQueryFactory(org.apache.lucene.index.Term term) {
            super(term);
        }
        
        @Override
        public TermQuery createQuery(FieldBoost boost,
                float dmqTieBreakerMultiplier, DocumentFrequencyCorrection dfc,
                boolean isBelowDMQ) throws IOException {
            return dfc == null ? new TermQuery(term) : new DependentTermQuery(term, dfc, boost);
        }
        
    }

}
