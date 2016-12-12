/**
 * 
 */
package querqy.lucene.rewrite;

import java.io.IOException;
import java.util.LinkedList;

import org.apache.lucene.search.DisjunctionMaxQuery;


/**
 * @author rene
 *
 */
public class DisjunctionMaxQueryFactory implements LuceneQueryFactory<DisjunctionMaxQuery> {

   protected final LinkedList<LuceneQueryFactory<?>> disjuncts;
   
   public DisjunctionMaxQueryFactory() {
       disjuncts = new LinkedList<>();
   }

   public void add(LuceneQueryFactory<?> disjunct) {
       disjuncts.add(disjunct);
   }

   public int getNumberOfDisjuncts() {
       return disjuncts.size();
   }

   public LuceneQueryFactory<?> getFirstDisjunct() {
       return disjuncts.getFirst();
   }

   @Override
   public DisjunctionMaxQuery createQuery(FieldBoost boost, float dmqTieBreakerMultiplier,
                                          DocumentFrequencyAndTermContextProvider dftcp, boolean isBelowDMQ)
           throws IOException {
       
       if ((!isBelowDMQ) && (dftcp != null)) {
           dftcp.newClause();
       }
       DisjunctionMaxQuery dmq = new DisjunctionMaxQuery(dmqTieBreakerMultiplier);
      

       for (LuceneQueryFactory<?> disjunct : disjuncts) {
           dmq.add(disjunct.createQuery(boost, dmqTieBreakerMultiplier, dftcp, true));
       }
       return dmq;
    }


}