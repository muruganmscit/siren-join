package solutions.siren.join.index.query;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.IOException;
import java.util.*;

import com.carrotsearch.hppc.LongHashSet;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.util.Accountable;
import org.apache.lucene.util.AttributeSource;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.RamUsageEstimator;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.index.fielddata.IndexFieldData;
import org.elasticsearch.index.fielddata.IndexNumericFieldData;
import org.elasticsearch.index.fielddata.SortedBinaryDocValues;

/**
 * Specialization for a disjunction over many terms, encoded in a byte array, that behaves like a
 * {@link ConstantScoreQuery} over a {@link BooleanQuery} containing only
 * {@link org.apache.lucene.search.BooleanClause.Occur#SHOULD} clauses.
 */
public abstract class FieldDataTermsQuery extends Query implements Accountable {

  private static final long BASE_RAM_BYTES_USED = RamUsageEstimator.shallowSizeOfInstance(FieldDataTermsQuery.class);

  /**
   * Reference to the encoded list of values for late decoding.
   */
  protected byte[] encodedTerms;

  /**
   * The field data for the field
   */
  protected final IndexFieldData fieldData;

  /**
   * The cache key for this query
   */
  protected final int cacheKey;

  /**
   * Get a {@link FieldDataTermsQuery} that filters on non-floating point numeric terms found in a hppc
   * {@link LongHashSet}.
   *
   * @param encodedTerms  An encoded set of terms.
   * @param fieldData     The fielddata for the field.
   * @param cacheKey      A unique key to use for caching this query.
   * @return the query.
   */
  public static FieldDataTermsQuery newLongs(final byte[] encodedTerms, final IndexNumericFieldData fieldData, final int cacheKey) {
    return new LongsFieldDataTermsQuery(encodedTerms, fieldData, cacheKey);
  }

  /**
   * Get a {@link FieldDataTermsQuery} that filters on non-numeric terms found in a hppc {@link LongHashSet} of
   * {@link BytesRef}.
   *
   * @param encodedTerms  An encoded set of terms.
   * @param fieldData     The fielddata for the field.
   * @param cacheKey      A unique key to use for caching this query.
   * @return the query.
   */
  public static FieldDataTermsQuery newBytes(final byte[] encodedTerms, final IndexFieldData fieldData, final int cacheKey) {
    return new BytesFieldDataTermsQuery(encodedTerms, fieldData, cacheKey);
  }

  /**
   * Creates a new {@link FieldDataTermsQuery} from the given field data.
   */
  public FieldDataTermsQuery(final byte[] encodedTerms, final IndexFieldData fieldData, final int cacheKey) {
    this.encodedTerms = encodedTerms;
    this.fieldData = fieldData;
    this.cacheKey = cacheKey;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!super.equals(obj)) {
      return false;
    }
    if (cacheKey != ((FieldDataTermsQuery) obj).cacheKey) { // relies on the cache key instead of the encodedTerms for equality
      return false;
    }
    return true;
  }

  @Override
  public int hashCode() {
    int hashcode = super.hashCode();
    hashcode = 31 * hashcode + cacheKey; // relies on the cache key instead of the encodedTerms for hashcode
    return hashcode;
  }

  @Override
  public Collection<Accountable> getChildResources() {
    return Collections.emptyList();
  }

  public abstract DocIdSet getDocIdSet(LeafReaderContext context) throws IOException;

  @Override
  public Weight createWeight(final IndexSearcher searcher, final boolean needsScores) throws IOException {
    return new ConstantScoreWeight(new CacheKeyFieldDataTermsQuery(cacheKey)) {

      @Override
      public void extractTerms(Set<Term> terms) {
        // no-op
        // This query is for abuse cases when the number of terms is too high to
        // run efficiently as a BooleanQuery. So likewise we hide its terms in
        // order to protect highlighters
      }

      private Scorer scorer(DocIdSet set) throws IOException {
        if (set == null) {
          return null;
        }
        final DocIdSetIterator disi = set.iterator();
        if (disi == null) {
          return null;
        }
        return new ConstantScoreScorer(this, score(), disi);
      }

      @Override
      public BulkScorer bulkScorer(LeafReaderContext context) throws IOException {
        final Scorer scorer = scorer(FieldDataTermsQuery.this.getDocIdSet(context));
        if (scorer == null) {
          return null;
        }
        return new DefaultBulkScorer(scorer);
      }

      @Override
      public Scorer scorer(LeafReaderContext context) throws IOException {
        return scorer(FieldDataTermsQuery.this.getDocIdSet(context));
      }
    };
  }

  /**
   * Filters on non-floating point numeric fields.
   */
  protected static class LongsFieldDataTermsQuery extends FieldDataTermsQuery {

    private LongHashSet terms;

    private final ESLogger logger = Loggers.getLogger(getClass());

    /**
     * Creates a new {@link FieldDataTermsQuery} from the given field data.
     *
     * @param fieldData
     */
    public LongsFieldDataTermsQuery(final byte[] encodedTerms, final IndexFieldData fieldData, final int cacheKey) {
      super(encodedTerms, fieldData, cacheKey);
    }

    @Override
    public long ramBytesUsed() {
      return BASE_RAM_BYTES_USED + (terms != null ? terms.size() * 8 : 0);
    }

    @Override
    public String toString(String defaultField) {
      final StringBuilder sb = new StringBuilder("LongsFieldDataTermsQuery:");
      return sb
              .append(defaultField)
              .append(":")
              // Do not serialise the full array, but instead the number of bytes - see issue #168
              .append("[size=" + (terms != null ? terms.size() * 8 : "0") + "]")
              .toString();
    }

    @Override
    public DocIdSet getDocIdSet(LeafReaderContext context) throws IOException {
      if (encodedTerms != null) { // late decoding of the encoded terms
        terms = this.decodeTerms(encodedTerms);
        encodedTerms = null; // release reference to the byte array to be able to reclaim memory
      }

      // make sure there are terms to filter on
      if (terms == null || terms.isEmpty()) return null;

      IndexNumericFieldData numericFieldData = (IndexNumericFieldData) fieldData;
      if (!numericFieldData.getNumericType().isFloatingPoint()) {
        final SortedNumericDocValues values = numericFieldData.load(context).getLongValues(); // load fielddata
        return new DocValuesDocIdSet(context.reader().maxDoc(), context.reader().getLiveDocs()) {
          @Override
          protected boolean matchDoc(int doc) {
            values.setDocument(doc);
            final int numVals = values.count();
            for (int i = 0; i < numVals; i++) {
              if (terms.contains(values.valueAt(i))) {
                return true;
              }
            }

            return false;
          }
        };
      }

      // only get here if wrong fielddata type in which case
      // no docs will match so we just return null.
      return null;
    }

    private final LongHashSet decodeTerms(byte[] encodedTerms) throws IOException {
      // Decodes the values and creates the long hash set
      long start = System.nanoTime();
      LongHashSet longHashSet = FieldDataTermsQueryHelper.decode(encodedTerms);
      logger.debug("{}: Deserialized {} terms - took {} ms", new Object[] { Thread.currentThread().getName(), longHashSet.size(), (System.nanoTime() - start) / 1000000 });
      return longHashSet;
    }

  }

  /**
   * Filters on non-numeric fields. Uses Sip hash to hash byte values before comparison.
   */
  protected static class BytesFieldDataTermsQuery extends FieldDataTermsQuery {

    private LongHashSet terms;

    private final ESLogger logger = Loggers.getLogger(getClass());

    /**
     * Creates a new {@link BytesFieldDataTermsQuery} from the given field data.
     *
     * @param fieldData
     */
    public BytesFieldDataTermsQuery(final byte[] encodedTerms, final IndexFieldData fieldData, final int cacheKey) {
      super(encodedTerms, fieldData, cacheKey);
    }

    @Override
    public long ramBytesUsed() {
      return BASE_RAM_BYTES_USED + (terms != null ? terms.size() * 8 : 0);
    }

    @Override
    public String toString(String defaultField) {
      final StringBuilder sb = new StringBuilder("BytesFieldDataTermsQuery:");
      return sb
              .append(defaultField)
              .append(":")
              // Do not serialise the full array, but instead the number of bytes - see issue #168
              .append("[size=" + (terms != null ? terms.size() * 8 : "0") + "]")
              .toString();
    }

    @Override
    public DocIdSet getDocIdSet(LeafReaderContext context) throws IOException {
      if (encodedTerms != null) { // late decoding of the encoded terms
        terms = this.decodeTerms(encodedTerms);
        encodedTerms = null; // release reference to the byte array to be able to reclaim memory
      }

      // make sure there are terms to filter on
      if (terms == null || terms.isEmpty()) return null;

      final SortedBinaryDocValues values = fieldData.load(context).getBytesValues(); // load fielddata
      return new DocValuesDocIdSet(context.reader().maxDoc(), context.reader().getLiveDocs()) {
        @Override
        protected boolean matchDoc(int doc) {
          values.setDocument(doc);
          final int numVals = values.count();
          for (int i = 0; i < numVals; i++) {
            final BytesRef term = values.valueAt(i);
            long termHash = FieldDataTermsQueryHelper.hash(term);
            if (terms.contains(termHash)) {
              return true;
            }
          }

          return false;
        }
      };
    }

    private final LongHashSet decodeTerms(byte[] encodedTerms) throws IOException {
      // Decodes the values and creates the long hash set
      long start = System.nanoTime();
      LongHashSet longHashSet = FieldDataTermsQueryHelper.decode(encodedTerms);
      logger.debug("{}: Deserialized {} terms - took {} ms", new Object[] { Thread.currentThread().getName(), longHashSet.size(), (System.nanoTime() - start) / 1000000 });
      return longHashSet;
    }

  }

  /**
   * <p>
   *   This query will be returned by the {@link ConstantScoreWeight} instead of the {@link FieldDataTermsQuery}
   *   and used by the
   *   {@link org.apache.lucene.search.LRUQueryCache.CachingWrapperWeight} to cache the query.
   *   This is necessary in order to avoid caching the byte array and long hash set, which is not memory friendly
   *   and not very efficient.
   * </p>
   * <p>
   *   Extends MultiTermQuery in order to be detected as "costly" query by {@link UsageTrackingQueryCachingPolicy}
   *   and trigger early caching.
   * </p>
   */
  private static class CacheKeyFieldDataTermsQuery extends MultiTermQuery {

    private final int cacheKey;

    public CacheKeyFieldDataTermsQuery(int cacheKey) {
      super("");
      this.cacheKey = cacheKey;
    }

    @Override
    public String toString(String field) {
      final StringBuilder sb = new StringBuilder("CacheKeyFieldDataTermsQuery:");
      return sb.append(field).append(":").append("[cacheKey=" + cacheKey + "]").toString();
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof CacheKeyFieldDataTermsQuery)) return false;
      CacheKeyFieldDataTermsQuery other = (CacheKeyFieldDataTermsQuery) o;
      return super.equals(o) && this.cacheKey == other.cacheKey;
    }

    @Override
    protected TermsEnum getTermsEnum(Terms terms, AttributeSource atts) throws IOException {
      return null;
    }

    @Override
    public int hashCode() {
      final int prime = 31;
      int result = 1;
      result = prime * result + cacheKey;
      return result;
    }

  }

}
