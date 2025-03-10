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
package org.apache.lucene.backward_index;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS;
import static org.apache.lucene.util.Version.LUCENE_9_0_0;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.document.BinaryDocValuesField;
import org.apache.lucene.document.BinaryPoint;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.DoubleDocValuesField;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.FloatDocValuesField;
import org.apache.lucene.document.FloatPoint;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.CheckIndex;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Fields;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.IndexCommit;
import org.apache.lucene.index.IndexFormatTooOldException;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexUpgrader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.LogByteSizeMergePolicy;
import org.apache.lucene.index.MergePolicy;
import org.apache.lucene.index.MultiBits;
import org.apache.lucene.index.MultiDocValues;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.SegmentCommitInfo;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.index.SegmentReader;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.StandardDirectoryReader;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermVectors;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.FieldExistsQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.WildcardQuery;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.NIOFSDirectory;
import org.apache.lucene.tests.analysis.MockAnalyzer;
import org.apache.lucene.tests.index.RandomIndexWriter;
import org.apache.lucene.tests.store.BaseDirectoryWrapper;
import org.apache.lucene.tests.util.LineFileDocs;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.InfoStream;
import org.apache.lucene.util.Version;
import org.junit.AfterClass;
import org.junit.BeforeClass;

/*
  Verify we can read previous versions' indexes, do searches
  against them, and add documents to them.
*/
// See: https://issues.apache.org/jira/browse/SOLR-12028 Tests cannot remove files on Windows
// machines occasionally
@SuppressWarnings("deprecation")
public class TestBackwardsCompatibility extends LuceneTestCase {

  // Backcompat index generation, described below, is mostly automated in:
  //
  //    dev-tools/scripts/addBackcompatIndexes.py
  //
  // For usage information, see:
  //
  //    http://wiki.apache.org/lucene-java/ReleaseTodo#Generate_Backcompat_Indexes
  //
  // -----
  //
  // To generate backcompat indexes with the current default codec, run the following gradle
  // command:
  //  gradlew test -Ptests.bwcdir=/path/to/store/indexes -Ptests.codec=default
  //               -Ptests.useSecurityManager=false --tests TestBackwardsCompatibility
  // Also add testmethod with one of the index creation methods below, for example:
  //    -Ptestmethod=testCreateCFS
  //
  // Zip up the generated indexes:
  //
  //    cd /path/to/store/indexes/index.cfs   ; zip index.<VERSION>-cfs.zip *
  //    cd /path/to/store/indexes/index.nocfs ; zip index.<VERSION>-nocfs.zip *
  //
  // Then move those 2 zip files to your trunk checkout and add them
  // to the oldNames array.

  private static final int DOCS_COUNT = 35;
  private static final int DELETED_ID = 7;

  private static final int KNN_VECTOR_MIN_SUPPORTED_VERSION = LUCENE_9_0_0.major;
  private static final String KNN_VECTOR_FIELD = "knn_field";
  private static final FieldType KNN_VECTOR_FIELD_TYPE =
      KnnFloatVectorField.createFieldType(3, VectorSimilarityFunction.COSINE);
  private static final float[] KNN_VECTOR = {0.2f, -0.1f, 0.1f};

  public void testCreateCFS() throws IOException {
    Path indexDir = getIndexDir().resolve("index.cfs");
    Files.deleteIfExists(indexDir);
    try (Directory dir = newFSDirectory(indexDir)) {
      createIndex(dir, true, false);
    }
  }

  public void testCreateNoCFS() throws IOException {
    Path indexDir = getIndexDir().resolve("index.nocfs");
    Files.deleteIfExists(indexDir);
    try (Directory dir = newFSDirectory(indexDir)) {
      createIndex(dir, false, false);
    }
  }

  // These are only needed for the special upgrade test to verify
  // that also single-segment indexes are correctly upgraded by IndexUpgrader.
  // You don't need them to be built for non-4.0 (the test is happy with just one
  // "old" segment format, version is unimportant:

  public void testCreateSingleSegmentCFS() throws IOException {
    Path indexDir = getIndexDir().resolve("index.singlesegment-cfs");
    Files.deleteIfExists(indexDir);
    try (Directory dir = newFSDirectory(indexDir)) {
      createIndex(dir, true, true);
    }
  }

  public void testCreateSingleSegmentNoCFS() throws IOException {
    Path indexDir = getIndexDir().resolve("index.singlesegment-nocfs");
    Files.deleteIfExists(indexDir);
    try (Directory dir = newFSDirectory(indexDir)) {
      createIndex(dir, false, true);
    }
  }

  public void testCreateIndexInternal() throws IOException {
    try (Directory dir = newDirectory()) {
      createIndex(dir, random().nextBoolean(), false);
      searchIndex(dir, Version.LATEST.toString(), Version.MIN_SUPPORTED_MAJOR, Version.LATEST);
    }
  }

  private Path getIndexDir() {
    String path = System.getProperty("tests.bwcdir");
    assumeTrue(
        "backcompat creation tests must be run with -Dtests.bwcdir=/path/to/write/indexes",
        path != null);
    return Paths.get(path);
  }

  public void testCreateMoreTermsIndex() throws Exception {
    Path indexDir = getIndexDir().resolve("moreterms");
    Files.deleteIfExists(indexDir);
    try (Directory dir = newFSDirectory(indexDir)) {
      createMoreTermsIndex(dir);
    }
  }

  public void testCreateMoreTermsIndexInternal() throws Exception {
    try (Directory dir = newDirectory()) {
      createMoreTermsIndex(dir);
    }
  }

  private void createMoreTermsIndex(Directory dir) throws Exception {
    LogByteSizeMergePolicy mp = new LogByteSizeMergePolicy();
    mp.setNoCFSRatio(1.0);
    mp.setMaxCFSSegmentSizeMB(Double.POSITIVE_INFINITY);
    MockAnalyzer analyzer = new MockAnalyzer(random());
    analyzer.setMaxTokenLength(TestUtil.nextInt(random(), 1, IndexWriter.MAX_TERM_LENGTH));

    IndexWriterConfig conf =
        new IndexWriterConfig(analyzer)
            .setMergePolicy(mp)
            .setCodec(TestUtil.getDefaultCodec())
            .setUseCompoundFile(false);
    IndexWriter writer = new IndexWriter(dir, conf);
    LineFileDocs docs = new LineFileDocs(new Random(0));
    for (int i = 0; i < 50; i++) {
      Document doc = TestUtil.cloneDocument(docs.nextDoc());
      doc.add(
          new NumericDocValuesField(
              "docid_intDV", doc.getField("docid_int").numericValue().longValue()));
      doc.add(
          new SortedDocValuesField("titleDV", new BytesRef(doc.getField("title").stringValue())));
      writer.addDocument(doc);
      if (i % 10 == 0) { // commit every 10 documents
        writer.commit();
      }
    }
    docs.close();
    writer.close();
    try (DirectoryReader reader = DirectoryReader.open(dir)) {
      searchExampleIndex(reader); // make sure we can search it
    }
  }

  // gradlew test -Ptestmethod=testCreateSortedIndex -Ptests.codec=default
  // -Ptests.useSecurityManager=false -Ptests.bwcdir=/tmp/sorted --tests TestBackwardsCompatibility
  public void testCreateSortedIndex() throws Exception {
    Path indexDir = getIndexDir().resolve("sorted");
    Files.deleteIfExists(indexDir);
    try (Directory dir = newFSDirectory(indexDir)) {
      createSortedIndex(dir);
    }
  }

  public void testCreateSortedIndexInternal() throws Exception {
    // this runs without the -Ptests.bwcdir=/tmp/sorted to make sure we can actually index and
    // search the created index
    try (Directory dir = newDirectory()) {
      createSortedIndex(dir);
    }
  }

  public void createSortedIndex(Directory dir) throws Exception {
    LogByteSizeMergePolicy mp = new LogByteSizeMergePolicy();
    mp.setNoCFSRatio(1.0);
    mp.setMaxCFSSegmentSizeMB(Double.POSITIVE_INFINITY);
    MockAnalyzer analyzer = new MockAnalyzer(random());
    analyzer.setMaxTokenLength(TestUtil.nextInt(random(), 1, IndexWriter.MAX_TERM_LENGTH));

    // TODO: remove randomness
    IndexWriterConfig conf = new IndexWriterConfig(analyzer);
    conf.setMergePolicy(mp);
    conf.setUseCompoundFile(false);
    conf.setCodec(TestUtil.getDefaultCodec());
    conf.setIndexSort(new Sort(new SortField("dateDV", SortField.Type.LONG, true)));
    IndexWriter writer = new IndexWriter(dir, conf);
    LineFileDocs docs = new LineFileDocs(new Random(0));
    SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
    parser.setTimeZone(TimeZone.getTimeZone("UTC"));
    ParsePosition position = new ParsePosition(0);
    for (int i = 0; i < 50; i++) {
      Document doc = TestUtil.cloneDocument(docs.nextDoc());
      String dateString = doc.get("date");
      position.setIndex(0);
      Date date = parser.parse(dateString, position);
      if (position.getErrorIndex() != -1) {
        throw new AssertionError("failed to parse \"" + dateString + "\" as date");
      }
      if (position.getIndex() != dateString.length()) {
        throw new AssertionError("failed to parse \"" + dateString + "\" as date");
      }
      doc.add(
          new NumericDocValuesField(
              "docid_intDV", doc.getField("docid_int").numericValue().longValue()));
      doc.add(
          new SortedDocValuesField("titleDV", new BytesRef(doc.getField("title").stringValue())));
      doc.add(new NumericDocValuesField("dateDV", date.getTime()));
      if (i % 10 == 0) { // commit every 10 documents
        writer.commit();
      }
      writer.addDocument(doc);
    }
    writer.forceMerge(1);
    writer.close();

    try (DirectoryReader reader = DirectoryReader.open(dir)) {
      searchExampleIndex(reader); // make sure we can search it
    }
  }

  private void updateNumeric(IndexWriter writer, String id, String f, String cf, long value)
      throws IOException {
    writer.updateNumericDocValue(new Term("id", id), f, value);
    writer.updateNumericDocValue(new Term("id", id), cf, value * 2);
  }

  private void updateBinary(IndexWriter writer, String id, String f, String cf, long value)
      throws IOException {
    writer.updateBinaryDocValue(new Term("id", id), f, toBytes(value));
    writer.updateBinaryDocValue(new Term("id", id), cf, toBytes(value * 2));
  }

  // Creates an index with DocValues updates
  public void testCreateIndexWithDocValuesUpdates() throws IOException {
    Path indexDir = getIndexDir().resolve("dvupdates");
    Files.deleteIfExists(indexDir);
    try (Directory dir = newFSDirectory(indexDir)) {
      createIndexWithDocValuesUpdates(dir);
      searchDocValuesUpdatesIndex(dir);
    }
  }

  public void testCreateIndexWithDocValuesUpdatesInternal() throws IOException {
    try (Directory dir = newDirectory()) {
      createIndexWithDocValuesUpdates(dir);
      searchDocValuesUpdatesIndex(dir);
    }
  }

  private void createIndexWithDocValuesUpdates(Directory dir) throws IOException {
    IndexWriterConfig conf =
        new IndexWriterConfig(new MockAnalyzer(random()))
            .setCodec(TestUtil.getDefaultCodec())
            .setUseCompoundFile(false)
            .setMergePolicy(NoMergePolicy.INSTANCE);
    IndexWriter writer = new IndexWriter(dir, conf);
    // create an index w/ few doc-values fields, some with updates and some without
    for (int i = 0; i < 30; i++) {
      Document doc = new Document();
      doc.add(new StringField("id", "" + i, Field.Store.NO));
      doc.add(new NumericDocValuesField("ndv1", i));
      doc.add(new NumericDocValuesField("ndv1_c", i * 2));
      doc.add(new NumericDocValuesField("ndv2", i * 3));
      doc.add(new NumericDocValuesField("ndv2_c", i * 6));
      doc.add(new BinaryDocValuesField("bdv1", toBytes(i)));
      doc.add(new BinaryDocValuesField("bdv1_c", toBytes(i * 2)));
      doc.add(new BinaryDocValuesField("bdv2", toBytes(i * 3)));
      doc.add(new BinaryDocValuesField("bdv2_c", toBytes(i * 6)));
      writer.addDocument(doc);
      if ((i + 1) % 10 == 0) {
        writer.commit(); // flush every 10 docs
      }
    }

    // first segment: no updates

    // second segment: update two fields, same gen
    updateNumeric(writer, "10", "ndv1", "ndv1_c", 100L);
    updateBinary(writer, "11", "bdv1", "bdv1_c", 100L);
    writer.commit();

    // third segment: update few fields, different gens, few docs
    updateNumeric(writer, "20", "ndv1", "ndv1_c", 100L);
    updateBinary(writer, "21", "bdv1", "bdv1_c", 100L);
    writer.commit();
    updateNumeric(writer, "22", "ndv1", "ndv1_c", 200L); // update the field again
    writer.close();
  }

  public void testCreateEmptyIndex() throws Exception {
    Path indexDir = getIndexDir().resolve("emptyIndex");
    Files.deleteIfExists(indexDir);
    IndexWriterConfig conf =
        new IndexWriterConfig(new MockAnalyzer(random()))
            .setUseCompoundFile(false)
            .setMergePolicy(NoMergePolicy.INSTANCE);
    try (Directory dir = newFSDirectory(indexDir);
        IndexWriter writer = new IndexWriter(dir, conf)) {
      writer.flush();
    }
  }

  static final String[] oldNames = {
    "9.0.0-cfs", // Force on separate lines
    "9.0.0-nocfs",
    "9.1.0-cfs",
    "9.1.0-nocfs",
    "9.2.0-cfs",
    "9.2.0-nocfs",
    "9.3.0-cfs",
    "9.3.0-nocfs",
    "9.4.0-cfs",
    "9.4.0-nocfs",
    "9.4.1-cfs",
    "9.4.1-nocfs",
    "9.4.2-cfs",
    "9.4.2-nocfs",
    "9.5.0-cfs",
    "9.5.0-nocfs",
    "9.6.0-cfs",
    "9.6.0-nocfs",
    "9.7.0-cfs",
    "9.7.0-nocfs",
    "9.8.0-cfs",
    "9.8.0-nocfs",
    "9.9.0-cfs",
    "9.9.0-nocfs",
    "9.9.1-cfs",
    "9.9.1-nocfs"
  };

  public static String[] getOldNames() {
    return oldNames;
  }

  static final String[] oldSortedNames = {
    "sorted.9.0.0", // Force on separate lines
    "sorted.9.1.0",
    "sorted.9.2.0",
    "sorted.9.3.0",
    "sorted.9.4.0",
    "sorted.9.4.1",
    "sorted.9.4.2",
    "sorted.9.5.0",
    "sorted.9.6.0",
    "sorted.9.7.0",
    "sorted.9.8.0",
    "sorted.9.9.0",
    "sorted.9.9.1"
  };

  public static String[] getOldSortedNames() {
    return oldSortedNames;
  }

  static final String[] unsupportedNames = {
    "1.9.0-cfs",
    "1.9.0-nocfs",
    "2.0.0-cfs",
    "2.0.0-nocfs",
    "2.1.0-cfs",
    "2.1.0-nocfs",
    "2.2.0-cfs",
    "2.2.0-nocfs",
    "2.3.0-cfs",
    "2.3.0-nocfs",
    "2.4.0-cfs",
    "2.4.0-nocfs",
    "2.4.1-cfs",
    "2.4.1-nocfs",
    "2.9.0-cfs",
    "2.9.0-nocfs",
    "2.9.1-cfs",
    "2.9.1-nocfs",
    "2.9.2-cfs",
    "2.9.2-nocfs",
    "2.9.3-cfs",
    "2.9.3-nocfs",
    "2.9.4-cfs",
    "2.9.4-nocfs",
    "3.0.0-cfs",
    "3.0.0-nocfs",
    "3.0.1-cfs",
    "3.0.1-nocfs",
    "3.0.2-cfs",
    "3.0.2-nocfs",
    "3.0.3-cfs",
    "3.0.3-nocfs",
    "3.1.0-cfs",
    "3.1.0-nocfs",
    "3.2.0-cfs",
    "3.2.0-nocfs",
    "3.3.0-cfs",
    "3.3.0-nocfs",
    "3.4.0-cfs",
    "3.4.0-nocfs",
    "3.5.0-cfs",
    "3.5.0-nocfs",
    "3.6.0-cfs",
    "3.6.0-nocfs",
    "3.6.1-cfs",
    "3.6.1-nocfs",
    "3.6.2-cfs",
    "3.6.2-nocfs",
    "4.0.0-cfs",
    "4.0.0-cfs",
    "4.0.0-nocfs",
    "4.0.0.1-cfs",
    "4.0.0.1-nocfs",
    "4.0.0.2-cfs",
    "4.0.0.2-nocfs",
    "4.1.0-cfs",
    "4.1.0-nocfs",
    "4.2.0-cfs",
    "4.2.0-nocfs",
    "4.2.1-cfs",
    "4.2.1-nocfs",
    "4.3.0-cfs",
    "4.3.0-nocfs",
    "4.3.1-cfs",
    "4.3.1-nocfs",
    "4.4.0-cfs",
    "4.4.0-nocfs",
    "4.5.0-cfs",
    "4.5.0-nocfs",
    "4.5.1-cfs",
    "4.5.1-nocfs",
    "4.6.0-cfs",
    "4.6.0-nocfs",
    "4.6.1-cfs",
    "4.6.1-nocfs",
    "4.7.0-cfs",
    "4.7.0-nocfs",
    "4.7.1-cfs",
    "4.7.1-nocfs",
    "4.7.2-cfs",
    "4.7.2-nocfs",
    "4.8.0-cfs",
    "4.8.0-nocfs",
    "4.8.1-cfs",
    "4.8.1-nocfs",
    "4.9.0-cfs",
    "4.9.0-nocfs",
    "4.9.1-cfs",
    "4.9.1-nocfs",
    "4.10.0-cfs",
    "4.10.0-nocfs",
    "4.10.1-cfs",
    "4.10.1-nocfs",
    "4.10.2-cfs",
    "4.10.2-nocfs",
    "4.10.3-cfs",
    "4.10.3-nocfs",
    "4.10.4-cfs",
    "4.10.4-nocfs",
    "5x-with-4x-segments-cfs",
    "5x-with-4x-segments-nocfs",
    "5.0.0.singlesegment-cfs",
    "5.0.0.singlesegment-nocfs",
    "5.0.0-cfs",
    "5.0.0-nocfs",
    "5.1.0-cfs",
    "5.1.0-nocfs",
    "5.2.0-cfs",
    "5.2.0-nocfs",
    "5.2.1-cfs",
    "5.2.1-nocfs",
    "5.3.0-cfs",
    "5.3.0-nocfs",
    "5.3.1-cfs",
    "5.3.1-nocfs",
    "5.3.2-cfs",
    "5.3.2-nocfs",
    "5.4.0-cfs",
    "5.4.0-nocfs",
    "5.4.1-cfs",
    "5.4.1-nocfs",
    "5.5.0-cfs",
    "5.5.0-nocfs",
    "5.5.1-cfs",
    "5.5.1-nocfs",
    "5.5.2-cfs",
    "5.5.2-nocfs",
    "5.5.3-cfs",
    "5.5.3-nocfs",
    "5.5.4-cfs",
    "5.5.4-nocfs",
    "5.5.5-cfs",
    "5.5.5-nocfs",
    "6.0.0-cfs",
    "6.0.0-nocfs",
    "6.0.1-cfs",
    "6.0.1-nocfs",
    "6.1.0-cfs",
    "6.1.0-nocfs",
    "6.2.0-cfs",
    "6.2.0-nocfs",
    "6.2.1-cfs",
    "6.2.1-nocfs",
    "6.3.0-cfs",
    "6.3.0-nocfs",
    "6.4.0-cfs",
    "6.4.0-nocfs",
    "6.4.1-cfs",
    "6.4.1-nocfs",
    "6.4.2-cfs",
    "6.4.2-nocfs",
    "6.5.0-cfs",
    "6.5.0-nocfs",
    "6.5.1-cfs",
    "6.5.1-nocfs",
    "6.6.0-cfs",
    "6.6.0-nocfs",
    "6.6.1-cfs",
    "6.6.1-nocfs",
    "6.6.2-cfs",
    "6.6.2-nocfs",
    "6.6.3-cfs",
    "6.6.3-nocfs",
    "6.6.4-cfs",
    "6.6.4-nocfs",
    "6.6.5-cfs",
    "6.6.5-nocfs",
    "6.6.6-cfs",
    "6.6.6-nocfs",
    "7.0.0-cfs",
    "7.0.0-nocfs",
    "7.0.1-cfs",
    "7.0.1-nocfs",
    "7.1.0-cfs",
    "7.1.0-nocfs",
    "7.2.0-cfs",
    "7.2.0-nocfs",
    "7.2.1-cfs",
    "7.2.1-nocfs",
    "7.3.0-cfs",
    "7.3.0-nocfs",
    "7.3.1-cfs",
    "7.3.1-nocfs",
    "7.4.0-cfs",
    "7.4.0-nocfs",
    "7.5.0-cfs",
    "7.5.0-nocfs",
    "7.6.0-cfs",
    "7.6.0-nocfs",
    "7.7.0-cfs",
    "7.7.0-nocfs",
    "7.7.1-cfs",
    "7.7.1-nocfs",
    "7.7.2-cfs",
    "7.7.2-nocfs",
    "7.7.3-cfs",
    "7.7.3-nocfs",
    "8.0.0-cfs",
    "8.0.0-nocfs",
    "8.1.0-cfs",
    "8.1.0-nocfs",
    "8.1.1-cfs",
    "8.1.1-nocfs",
    "8.2.0-cfs",
    "8.2.0-nocfs",
    "8.3.0-cfs",
    "8.3.0-nocfs",
    "8.3.1-cfs",
    "8.3.1-nocfs",
    "8.4.0-cfs",
    "8.4.0-nocfs",
    "8.4.1-cfs",
    "8.4.1-nocfs",
    "8.5.0-cfs",
    "8.5.0-nocfs",
    "8.5.1-cfs",
    "8.5.1-nocfs",
    "8.5.2-cfs",
    "8.5.2-nocfs",
    "8.6.0-cfs",
    "8.6.0-nocfs",
    "8.6.1-cfs",
    "8.6.1-nocfs",
    "8.6.2-cfs",
    "8.6.2-nocfs",
    "8.6.3-cfs",
    "8.6.3-nocfs",
    "8.7.0-cfs",
    "8.7.0-nocfs",
    "8.8.0-cfs",
    "8.8.0-nocfs",
    "8.8.1-cfs",
    "8.8.1-nocfs",
    "8.8.2-cfs",
    "8.8.2-nocfs",
    "8.9.0-cfs",
    "8.9.0-nocfs",
    "8.10.0-cfs",
    "8.10.0-nocfs",
    "8.10.1-cfs",
    "8.10.1-nocfs",
    "8.11.0-cfs",
    "8.11.0-nocfs",
    "8.11.1-cfs",
    "8.11.1-nocfs",
    "8.11.2-cfs",
    "8.11.2-nocfs"
  };

  static final int MIN_BINARY_SUPPORTED_MAJOR = Version.MIN_SUPPORTED_MAJOR - 1;

  static final String[] binarySupportedNames;

  static {
    ArrayList<String> list = new ArrayList<>();
    for (String name : unsupportedNames) {
      if (name.startsWith(MIN_BINARY_SUPPORTED_MAJOR + ".")) {
        list.add(name);
      }
    }
    binarySupportedNames = list.toArray(new String[0]);
  }

  // TODO: on 6.0.0 release, gen the single segment indices and add here:
  static final String[] oldSingleSegmentNames = {};

  public static String[] getOldSingleSegmentNames() {
    return oldSingleSegmentNames;
  }

  static Map<String, Directory> oldIndexDirs;

  /** Randomizes the use of some of hte constructor variations */
  private static IndexUpgrader newIndexUpgrader(Directory dir) {
    final boolean streamType = random().nextBoolean();
    final int choice = TestUtil.nextInt(random(), 0, 2);
    switch (choice) {
      case 0:
        return new IndexUpgrader(dir);
      case 1:
        return new IndexUpgrader(dir, streamType ? null : InfoStream.NO_OUTPUT, false);
      case 2:
        return new IndexUpgrader(dir, newIndexWriterConfig(null), false);
      default:
        fail("case statement didn't get updated when random bounds changed");
    }
    return null; // never get here
  }

  @BeforeClass
  public static void beforeClass() throws Exception {
    List<String> names = new ArrayList<>(oldNames.length + oldSingleSegmentNames.length);
    names.addAll(Arrays.asList(oldNames));
    names.addAll(Arrays.asList(oldSingleSegmentNames));
    oldIndexDirs = new HashMap<>();
    for (String name : names) {
      Path dir = createTempDir(name);
      InputStream resource =
          TestBackwardsCompatibility.class.getResourceAsStream("index." + name + ".zip");
      assertNotNull("Index name " + name + " not found", resource);
      TestUtil.unzip(resource, dir);
      oldIndexDirs.put(name, newFSDirectory(dir));
    }
  }

  @AfterClass
  public static void afterClass() throws Exception {
    for (Directory d : oldIndexDirs.values()) {
      d.close();
    }
    oldIndexDirs = null;
  }

  public void testAllVersionHaveCfsAndNocfs() {
    // ensure all tested versions with cfs also have nocfs
    String[] files = new String[oldNames.length];
    System.arraycopy(oldNames, 0, files, 0, oldNames.length);
    Arrays.sort(files);
    String prevFile = "";
    for (String file : files) {
      if (prevFile.endsWith("-cfs")) {
        String prefix = prevFile.replace("-cfs", "");
        assertEquals("Missing -nocfs for backcompat index " + prefix, prefix + "-nocfs", file);
      }
      prevFile = file;
    }
  }

  public void testAllVersionsTested() throws Exception {
    Pattern constantPattern = Pattern.compile("LUCENE_(\\d+)_(\\d+)_(\\d+)(_ALPHA|_BETA)?");
    // find the unique versions according to Version.java
    List<String> expectedVersions = new ArrayList<>();
    for (java.lang.reflect.Field field : Version.class.getDeclaredFields()) {
      if (Modifier.isStatic(field.getModifiers()) && field.getType() == Version.class) {
        Version v = (Version) field.get(Version.class);
        if (v.equals(Version.LATEST)) {
          continue;
        }

        Matcher constant = constantPattern.matcher(field.getName());
        if (constant.matches() == false) {
          continue;
        }

        expectedVersions.add(v + "-cfs");
      }
    }

    // BEGIN TRUNK ONLY BLOCK
    // on trunk, the last release of the prev major release is also untested
    Version lastPrevMajorVersion = null;
    for (java.lang.reflect.Field field : Version.class.getDeclaredFields()) {
      if (Modifier.isStatic(field.getModifiers()) && field.getType() == Version.class) {
        Version v = (Version) field.get(Version.class);
        Matcher constant = constantPattern.matcher(field.getName());
        if (constant.matches() == false) continue;
        if (v.major == Version.LATEST.major - 1
            && (lastPrevMajorVersion == null || v.onOrAfter(lastPrevMajorVersion))) {
          lastPrevMajorVersion = v;
        }
      }
    }
    assertNotNull(lastPrevMajorVersion);
    expectedVersions.remove(lastPrevMajorVersion + "-cfs");
    // END TRUNK ONLY BLOCK

    Collections.sort(expectedVersions);

    // find what versions we are testing
    List<String> testedVersions = new ArrayList<>();
    for (String testedVersion : oldNames) {
      if (testedVersion.endsWith("-cfs") == false) {
        continue;
      }
      testedVersions.add(testedVersion);
    }
    Collections.sort(testedVersions);

    int i = 0;
    int j = 0;
    List<String> missingFiles = new ArrayList<>();
    List<String> extraFiles = new ArrayList<>();
    while (i < expectedVersions.size() && j < testedVersions.size()) {
      String expectedVersion = expectedVersions.get(i);
      String testedVersion = testedVersions.get(j);
      int compare = expectedVersion.compareTo(testedVersion);
      if (compare == 0) { // equal, we can move on
        ++i;
        ++j;
      } else if (compare < 0) { // didn't find test for version constant
        missingFiles.add(expectedVersion);
        ++i;
      } else { // extra test file
        extraFiles.add(testedVersion);
        ++j;
      }
    }
    while (i < expectedVersions.size()) {
      missingFiles.add(expectedVersions.get(i));
      ++i;
    }
    while (j < testedVersions.size()) {
      missingFiles.add(testedVersions.get(j));
      ++j;
    }

    // we could be missing up to 1 file, which may be due to a release that is in progress
    if (missingFiles.size() <= 1 && extraFiles.isEmpty()) {
      // success
      return;
    }

    StringBuilder msg = new StringBuilder();
    if (missingFiles.size() > 1) {
      msg.append("Missing backcompat test files:\n");
      for (String missingFile : missingFiles) {
        msg.append("  ").append(missingFile).append("\n");
      }
    }
    if (extraFiles.isEmpty() == false) {
      msg.append("Extra backcompat test files:\n");
      for (String extraFile : extraFiles) {
        msg.append("  ").append(extraFile).append("\n");
      }
    }
    fail(msg.toString());
  }

  /**
   * This test checks that *only* IndexFormatTooOldExceptions are thrown when you open and operate
   * on too old indexes!
   */
  public void testUnsupportedOldIndexes() throws Exception {
    for (int i = 0; i < unsupportedNames.length; i++) {
      if (VERBOSE) {
        System.out.println("TEST: index " + unsupportedNames[i]);
      }
      Path oldIndexDir = createTempDir(unsupportedNames[i]);
      TestUtil.unzip(
          getDataInputStream("unsupported." + unsupportedNames[i] + ".zip"), oldIndexDir);
      BaseDirectoryWrapper dir = newFSDirectory(oldIndexDir);
      // don't checkindex, these are intentionally not supported
      dir.setCheckIndexOnClose(false);

      IndexReader reader = null;
      IndexWriter writer = null;
      try {
        reader = DirectoryReader.open(dir);
        fail("DirectoryReader.open should not pass for " + unsupportedNames[i]);
      } catch (IndexFormatTooOldException e) {
        if (e.getReason() != null) {
          assertNull(e.getVersion());
          assertNull(e.getMinVersion());
          assertNull(e.getMaxVersion());
          assertEquals(
              e.getMessage(),
              new IndexFormatTooOldException(e.getResourceDescription(), e.getReason())
                  .getMessage());
        } else {
          assertNotNull(e.getVersion());
          assertNotNull(e.getMinVersion());
          assertNotNull(e.getMaxVersion());
          assertTrue(e.getMessage(), e.getMaxVersion() >= e.getMinVersion());
          assertTrue(
              e.getMessage(),
              e.getMaxVersion() < e.getVersion() || e.getVersion() < e.getMinVersion());
          assertEquals(
              e.getMessage(),
              new IndexFormatTooOldException(
                      e.getResourceDescription(),
                      e.getVersion(),
                      e.getMinVersion(),
                      e.getMaxVersion())
                  .getMessage());
        }
        // pass
        if (VERBOSE) {
          System.out.println("TEST: got expected exc:");
          e.printStackTrace(System.out);
        }
      } finally {
        if (reader != null) reader.close();
        reader = null;
      }

      try {
        writer =
            new IndexWriter(
                dir, newIndexWriterConfig(new MockAnalyzer(random())).setCommitOnClose(false));
        fail("IndexWriter creation should not pass for " + unsupportedNames[i]);
      } catch (IndexFormatTooOldException e) {
        if (e.getReason() != null) {
          assertNull(e.getVersion());
          assertNull(e.getMinVersion());
          assertNull(e.getMaxVersion());
          assertEquals(
              e.getMessage(),
              new IndexFormatTooOldException(e.getResourceDescription(), e.getReason())
                  .getMessage());
        } else {
          assertNotNull(e.getVersion());
          assertNotNull(e.getMinVersion());
          assertNotNull(e.getMaxVersion());
          assertTrue(e.getMessage(), e.getMaxVersion() >= e.getMinVersion());
          assertTrue(
              e.getMessage(),
              e.getMaxVersion() < e.getVersion() || e.getVersion() < e.getMinVersion());
          assertEquals(
              e.getMessage(),
              new IndexFormatTooOldException(
                      e.getResourceDescription(),
                      e.getVersion(),
                      e.getMinVersion(),
                      e.getMaxVersion())
                  .getMessage());
        }
        // pass
        if (VERBOSE) {
          System.out.println("TEST: got expected exc:");
          e.printStackTrace(System.out);
        }
        // Make sure exc message includes a path=
        assertTrue("got exc message: " + e.getMessage(), e.getMessage().contains("path=\""));
      } finally {
        // we should fail to open IW, and so it should be null when we get here.
        // However, if the test fails (i.e., IW did not fail on open), we need
        // to close IW. However, if merges are run, IW may throw
        // IndexFormatTooOldException, and we don't want to mask the fail()
        // above, so close without waiting for merges.
        if (writer != null) {
          try {
            writer.commit();
          } finally {
            writer.close();
          }
        }
      }

      ByteArrayOutputStream bos = new ByteArrayOutputStream(1024);
      CheckIndex checker = new CheckIndex(dir);
      checker.setInfoStream(new PrintStream(bos, false, UTF_8));
      CheckIndex.Status indexStatus = checker.checkIndex();
      if (unsupportedNames[i].startsWith("8.")) {
        assertTrue(indexStatus.clean);
      } else {
        assertFalse(indexStatus.clean);
        // CheckIndex doesn't enforce a minimum version, so we either get an
        // IndexFormatTooOldException
        // or an IllegalArgumentException saying that the codec doesn't exist.
        boolean formatTooOld =
            bos.toString(UTF_8).contains(IndexFormatTooOldException.class.getName());
        boolean missingCodec = bos.toString(UTF_8).contains("Could not load codec");
        assertTrue(formatTooOld || missingCodec);
      }
      checker.close();

      dir.close();
    }
  }

  public void testFullyMergeOldIndex() throws Exception {
    for (String name : oldNames) {
      if (VERBOSE) {
        System.out.println("\nTEST: index=" + name);
      }
      Directory dir = newDirectory(oldIndexDirs.get(name));

      final SegmentInfos oldSegInfos = SegmentInfos.readLatestCommit(dir);

      IndexWriter w = new IndexWriter(dir, new IndexWriterConfig(new MockAnalyzer(random())));
      w.forceMerge(1);
      w.close();

      final SegmentInfos segInfos = SegmentInfos.readLatestCommit(dir);
      assertEquals(
          oldSegInfos.getIndexCreatedVersionMajor(), segInfos.getIndexCreatedVersionMajor());
      assertEquals(Version.LATEST, segInfos.asList().get(0).info.getVersion());
      assertEquals(
          oldSegInfos.asList().get(0).info.getMinVersion(),
          segInfos.asList().get(0).info.getMinVersion());

      dir.close();
    }
  }

  public void testAddOldIndexes() throws IOException {
    for (String name : oldNames) {
      if (VERBOSE) {
        System.out.println("\nTEST: old index " + name);
      }
      Directory oldDir = oldIndexDirs.get(name);
      SegmentInfos infos = SegmentInfos.readLatestCommit(oldDir);

      Directory targetDir = newDirectory();
      if (infos.getCommitLuceneVersion().major != Version.LATEST.major) {
        // both indexes are not compatible
        Directory targetDir2 = newDirectory();
        IndexWriter w =
            new IndexWriter(targetDir2, newIndexWriterConfig(new MockAnalyzer(random())));
        IllegalArgumentException e =
            expectThrows(IllegalArgumentException.class, () -> w.addIndexes(oldDir));
        assertTrue(
            e.getMessage(),
            e.getMessage()
                .startsWith(
                    "Cannot use addIndexes(Directory) with indexes that have been created by a different Lucene version."));
        w.close();
        targetDir2.close();

        // for the next test, we simulate writing to an index that was created on the same major
        // version
        new SegmentInfos(infos.getIndexCreatedVersionMajor()).commit(targetDir);
      }

      IndexWriter w = new IndexWriter(targetDir, newIndexWriterConfig(new MockAnalyzer(random())));
      w.addIndexes(oldDir);
      w.close();

      SegmentInfos si = SegmentInfos.readLatestCommit(targetDir);
      assertNull(
          "none of the segments should have been upgraded",
          si.asList().stream()
              .filter( // depending on the MergePolicy we might see these segments merged away
                  sci ->
                      sci.getId() != null
                          && sci.info.getVersion().onOrAfter(Version.fromBits(8, 6, 0)) == false)
              .findAny()
              .orElse(null));
      if (VERBOSE) {
        System.out.println("\nTEST: done adding indices; now close");
      }

      targetDir.close();
    }
  }

  public void testAddOldIndexesReader() throws IOException {
    for (String name : oldNames) {
      Directory oldDir = oldIndexDirs.get(name);
      SegmentInfos infos = SegmentInfos.readLatestCommit(oldDir);
      DirectoryReader reader = DirectoryReader.open(oldDir);

      Directory targetDir = newDirectory();
      if (infos.getCommitLuceneVersion().major != Version.LATEST.major) {
        Directory targetDir2 = newDirectory();
        IndexWriter w =
            new IndexWriter(targetDir2, newIndexWriterConfig(new MockAnalyzer(random())));
        IllegalArgumentException e =
            expectThrows(
                IllegalArgumentException.class, () -> TestUtil.addIndexesSlowly(w, reader));
        assertEquals(
            e.getMessage(),
            "Cannot merge a segment that has been created with major version 9 into this index which has been created by major version 10");
        w.close();
        targetDir2.close();

        // for the next test, we simulate writing to an index that was created on the same major
        // version
        new SegmentInfos(infos.getIndexCreatedVersionMajor()).commit(targetDir);
      }
      IndexWriter w = new IndexWriter(targetDir, newIndexWriterConfig(new MockAnalyzer(random())));
      TestUtil.addIndexesSlowly(w, reader);
      w.close();
      reader.close();
      SegmentInfos si = SegmentInfos.readLatestCommit(targetDir);
      assertNull(
          "all SCIs should have an id now",
          si.asList().stream().filter(sci -> sci.getId() == null).findAny().orElse(null));
      targetDir.close();
    }
  }

  public void testSearchOldIndex() throws Exception {
    for (String name : oldNames) {
      Version version = Version.parse(name.substring(0, name.indexOf('-')));
      searchIndex(oldIndexDirs.get(name), name, Version.MIN_SUPPORTED_MAJOR, version);
    }

    if (TEST_NIGHTLY) {
      for (String name : binarySupportedNames) {
        Path oldIndexDir = createTempDir(name);
        TestUtil.unzip(getDataInputStream("unsupported." + name + ".zip"), oldIndexDir);
        try (BaseDirectoryWrapper dir = newFSDirectory(oldIndexDir)) {
          Version version = Version.parse(name.substring(0, name.indexOf('-')));
          searchIndex(dir, name, MIN_BINARY_SUPPORTED_MAJOR, version);
        }
      }
    }
  }

  public void testIndexOldIndexNoAdds() throws Exception {
    for (String name : oldNames) {
      Directory dir = newDirectory(oldIndexDirs.get(name));
      Version version = Version.parse(name.substring(0, name.indexOf('-')));
      changeIndexNoAdds(random(), dir, version);
      dir.close();
    }
  }

  public void testIndexOldIndex() throws Exception {
    for (String name : oldNames) {
      if (VERBOSE) {
        System.out.println("TEST: oldName=" + name);
      }
      Directory dir = newDirectory(oldIndexDirs.get(name));
      Version v = Version.parse(name.substring(0, name.indexOf('-')));
      changeIndexWithAdds(random(), dir, v);
      dir.close();
    }
  }

  private void doTestHits(ScoreDoc[] hits, int expectedCount, IndexReader reader)
      throws IOException {
    final int hitCount = hits.length;
    assertEquals("wrong number of hits", expectedCount, hitCount);
    StoredFields storedFields = reader.storedFields();
    TermVectors termVectors = reader.termVectors();
    for (ScoreDoc hit : hits) {
      storedFields.document(hit.doc);
      termVectors.get(hit.doc);
    }
  }

  public void searchIndex(
      Directory dir, String oldName, int minIndexMajorVersion, Version nameVersion)
      throws IOException {
    // QueryParser parser = new QueryParser("contents", new MockAnalyzer(random));
    // Query query = parser.parse("handle:1");
    IndexCommit indexCommit = DirectoryReader.listCommits(dir).get(0);
    IndexReader reader = DirectoryReader.open(indexCommit, minIndexMajorVersion, null);
    IndexSearcher searcher = newSearcher(reader);

    TestUtil.checkIndex(dir);

    final Bits liveDocs = MultiBits.getLiveDocs(reader);
    assertNotNull(liveDocs);

    StoredFields storedFields = reader.storedFields();
    TermVectors termVectors = reader.termVectors();

    for (int i = 0; i < DOCS_COUNT; i++) {
      if (liveDocs.get(i)) {
        Document d = storedFields.document(i);
        List<IndexableField> fields = d.getFields();
        boolean isProxDoc = d.getField("content3") == null;
        if (isProxDoc) {
          assertEquals(7, fields.size());
          IndexableField f = d.getField("id");
          assertEquals("" + i, f.stringValue());

          f = d.getField("utf8");
          assertEquals(
              "Lu\uD834\uDD1Ece\uD834\uDD60ne \u0000 \u2620 ab\ud917\udc17cd", f.stringValue());

          f = d.getField("autf8");
          assertEquals(
              "Lu\uD834\uDD1Ece\uD834\uDD60ne \u0000 \u2620 ab\ud917\udc17cd", f.stringValue());

          f = d.getField("content2");
          assertEquals("here is more content with aaa aaa aaa", f.stringValue());

          f = d.getField("fie\u2C77ld");
          assertEquals("field with non-ascii name", f.stringValue());
        }

        Fields tfvFields = termVectors.get(i);
        assertNotNull("i=" + i, tfvFields);
        Terms tfv = tfvFields.terms("utf8");
        assertNotNull("docID=" + i + " index=" + oldName, tfv);
      } else {
        assertEquals(DELETED_ID, i);
      }
    }

    // check docvalues fields
    NumericDocValues dvByte = MultiDocValues.getNumericValues(reader, "dvByte");
    BinaryDocValues dvBytesDerefFixed = MultiDocValues.getBinaryValues(reader, "dvBytesDerefFixed");
    BinaryDocValues dvBytesDerefVar = MultiDocValues.getBinaryValues(reader, "dvBytesDerefVar");
    SortedDocValues dvBytesSortedFixed =
        MultiDocValues.getSortedValues(reader, "dvBytesSortedFixed");
    SortedDocValues dvBytesSortedVar = MultiDocValues.getSortedValues(reader, "dvBytesSortedVar");
    BinaryDocValues dvBytesStraightFixed =
        MultiDocValues.getBinaryValues(reader, "dvBytesStraightFixed");
    BinaryDocValues dvBytesStraightVar =
        MultiDocValues.getBinaryValues(reader, "dvBytesStraightVar");
    NumericDocValues dvDouble = MultiDocValues.getNumericValues(reader, "dvDouble");
    NumericDocValues dvFloat = MultiDocValues.getNumericValues(reader, "dvFloat");
    NumericDocValues dvInt = MultiDocValues.getNumericValues(reader, "dvInt");
    NumericDocValues dvLong = MultiDocValues.getNumericValues(reader, "dvLong");
    NumericDocValues dvPacked = MultiDocValues.getNumericValues(reader, "dvPacked");
    NumericDocValues dvShort = MultiDocValues.getNumericValues(reader, "dvShort");

    SortedSetDocValues dvSortedSet = MultiDocValues.getSortedSetValues(reader, "dvSortedSet");
    SortedNumericDocValues dvSortedNumeric =
        MultiDocValues.getSortedNumericValues(reader, "dvSortedNumeric");

    for (int i = 0; i < DOCS_COUNT; i++) {
      int id = Integer.parseInt(storedFields.document(i).get("id"));
      assertEquals(i, dvByte.nextDoc());
      assertEquals(id, dvByte.longValue());

      byte[] bytes =
          new byte[] {(byte) (id >>> 24), (byte) (id >>> 16), (byte) (id >>> 8), (byte) id};
      BytesRef expectedRef = new BytesRef(bytes);

      assertEquals(i, dvBytesDerefFixed.nextDoc());
      BytesRef term = dvBytesDerefFixed.binaryValue();
      assertEquals(expectedRef, term);
      assertEquals(i, dvBytesDerefVar.nextDoc());
      term = dvBytesDerefVar.binaryValue();
      assertEquals(expectedRef, term);
      assertEquals(i, dvBytesSortedFixed.nextDoc());
      term = dvBytesSortedFixed.lookupOrd(dvBytesSortedFixed.ordValue());
      assertEquals(expectedRef, term);
      assertEquals(i, dvBytesSortedVar.nextDoc());
      term = dvBytesSortedVar.lookupOrd(dvBytesSortedVar.ordValue());
      assertEquals(expectedRef, term);
      assertEquals(i, dvBytesStraightFixed.nextDoc());
      term = dvBytesStraightFixed.binaryValue();
      assertEquals(expectedRef, term);
      assertEquals(i, dvBytesStraightVar.nextDoc());
      term = dvBytesStraightVar.binaryValue();
      assertEquals(expectedRef, term);

      assertEquals(i, dvDouble.nextDoc());
      assertEquals(id, Double.longBitsToDouble(dvDouble.longValue()), 0D);
      assertEquals(i, dvFloat.nextDoc());
      assertEquals((float) id, Float.intBitsToFloat((int) dvFloat.longValue()), 0F);
      assertEquals(i, dvInt.nextDoc());
      assertEquals(id, dvInt.longValue());
      assertEquals(i, dvLong.nextDoc());
      assertEquals(id, dvLong.longValue());
      assertEquals(i, dvPacked.nextDoc());
      assertEquals(id, dvPacked.longValue());
      assertEquals(i, dvShort.nextDoc());
      assertEquals(id, dvShort.longValue());

      assertEquals(i, dvSortedSet.nextDoc());
      assertEquals(1, dvSortedSet.docValueCount());
      long ord = dvSortedSet.nextOrd();
      term = dvSortedSet.lookupOrd(ord);
      assertEquals(expectedRef, term);

      assertEquals(i, dvSortedNumeric.nextDoc());
      assertEquals(1, dvSortedNumeric.docValueCount());
      assertEquals(id, dvSortedNumeric.nextValue());
    }

    ScoreDoc[] hits = searcher.search(new TermQuery(new Term("content", "aaa")), 1000).scoreDocs;

    // First document should be #0
    Document d = storedFields.document(hits[0].doc);
    assertEquals("didn't get the right document first", "0", d.get("id"));

    doTestHits(hits, 34, searcher.getIndexReader());

    hits = searcher.search(new TermQuery(new Term("content5", "aaa")), 1000).scoreDocs;

    doTestHits(hits, 34, searcher.getIndexReader());

    hits = searcher.search(new TermQuery(new Term("content6", "aaa")), 1000).scoreDocs;

    doTestHits(hits, 34, searcher.getIndexReader());

    hits = searcher.search(new TermQuery(new Term("utf8", "\u0000")), 1000).scoreDocs;
    assertEquals(34, hits.length);
    hits =
        searcher.search(new TermQuery(new Term("utf8", "lu\uD834\uDD1Ece\uD834\uDD60ne")), 1000)
            .scoreDocs;
    assertEquals(34, hits.length);
    hits = searcher.search(new TermQuery(new Term("utf8", "ab\ud917\udc17cd")), 1000).scoreDocs;
    assertEquals(34, hits.length);

    doTestHits(
        searcher.search(IntPoint.newRangeQuery("intPoint1d", 0, 34), 1000).scoreDocs,
        34,
        searcher.getIndexReader());
    doTestHits(
        searcher.search(
                IntPoint.newRangeQuery("intPoint2d", new int[] {0, 0}, new int[] {34, 68}), 1000)
            .scoreDocs,
        34,
        searcher.getIndexReader());
    doTestHits(
        searcher.search(FloatPoint.newRangeQuery("floatPoint1d", 0f, 34f), 1000).scoreDocs,
        34,
        searcher.getIndexReader());
    doTestHits(
        searcher.search(
                FloatPoint.newRangeQuery(
                    "floatPoint2d", new float[] {0f, 0f}, new float[] {34f, 68f}),
                1000)
            .scoreDocs,
        34,
        searcher.getIndexReader());
    doTestHits(
        searcher.search(LongPoint.newRangeQuery("longPoint1d", 0, 34), 1000).scoreDocs,
        34,
        searcher.getIndexReader());
    doTestHits(
        searcher.search(
                LongPoint.newRangeQuery("longPoint2d", new long[] {0, 0}, new long[] {34, 68}),
                1000)
            .scoreDocs,
        34,
        searcher.getIndexReader());
    doTestHits(
        searcher.search(DoublePoint.newRangeQuery("doublePoint1d", 0.0, 34.0), 1000).scoreDocs,
        34,
        searcher.getIndexReader());
    doTestHits(
        searcher.search(
                DoublePoint.newRangeQuery(
                    "doublePoint2d", new double[] {0.0, 0.0}, new double[] {34.0, 68.0}),
                1000)
            .scoreDocs,
        34,
        searcher.getIndexReader());

    byte[] bytes1 = new byte[4];
    byte[] bytes2 = new byte[] {0, 0, 0, (byte) 34};
    doTestHits(
        searcher.search(BinaryPoint.newRangeQuery("binaryPoint1d", bytes1, bytes2), 1000).scoreDocs,
        34,
        searcher.getIndexReader());
    byte[] bytes3 = new byte[] {0, 0, 0, (byte) 68};
    doTestHits(
        searcher.search(
                BinaryPoint.newRangeQuery(
                    "binaryPoint2d", new byte[][] {bytes1, bytes1}, new byte[][] {bytes2, bytes3}),
                1000)
            .scoreDocs,
        34,
        searcher.getIndexReader());

    // test vector values and KNN search
    if (nameVersion.major >= KNN_VECTOR_MIN_SUPPORTED_VERSION) {
      // test vector values
      int cnt = 0;
      for (LeafReaderContext ctx : reader.leaves()) {
        FloatVectorValues values = ctx.reader().getFloatVectorValues(KNN_VECTOR_FIELD);
        if (values != null) {
          assertEquals(KNN_VECTOR_FIELD_TYPE.vectorDimension(), values.dimension());
          for (int doc = values.nextDoc(); doc != NO_MORE_DOCS; doc = values.nextDoc()) {
            float[] expectedVector = {KNN_VECTOR[0], KNN_VECTOR[1], KNN_VECTOR[2] + 0.1f * cnt};
            assertArrayEquals(
                "vectors do not match for doc=" + cnt, expectedVector, values.vectorValue(), 0);
            cnt++;
          }
        }
      }
      assertEquals(DOCS_COUNT, cnt);

      // test KNN search
      ScoreDoc[] scoreDocs = assertKNNSearch(searcher, KNN_VECTOR, 10, 10, "0");
      for (int i = 0; i < scoreDocs.length; i++) {
        int id = Integer.parseInt(storedFields.document(scoreDocs[i].doc).get("id"));
        int expectedId = i < DELETED_ID ? i : i + 1;
        assertEquals(expectedId, id);
      }
    }

    reader.close();
  }

  private static ScoreDoc[] assertKNNSearch(
      IndexSearcher searcher,
      float[] queryVector,
      int k,
      int expectedHitsCount,
      String expectedFirstDocId)
      throws IOException {
    ScoreDoc[] hits =
        searcher.search(new KnnFloatVectorQuery(KNN_VECTOR_FIELD, queryVector, k), k).scoreDocs;
    assertEquals("wrong number of hits", expectedHitsCount, hits.length);
    Document d = searcher.storedFields().document(hits[0].doc);
    assertEquals("wrong first document", expectedFirstDocId, d.get("id"));
    return hits;
  }

  public void changeIndexWithAdds(Random random, Directory dir, Version nameVersion)
      throws IOException {
    SegmentInfos infos = SegmentInfos.readLatestCommit(dir);
    assertEquals(nameVersion, infos.getCommitLuceneVersion());
    assertEquals(nameVersion, infos.getMinSegmentLuceneVersion());

    // open writer
    IndexWriter writer =
        new IndexWriter(
            dir,
            newIndexWriterConfig(new MockAnalyzer(random))
                .setOpenMode(OpenMode.APPEND)
                .setMergePolicy(newLogMergePolicy()));
    // add 10 docs
    for (int i = 0; i < 10; i++) {
      addDoc(writer, DOCS_COUNT + i);
    }

    // make sure writer sees right total -- writer seems not to know about deletes in .del?
    final int expected = 45;
    assertEquals("wrong doc count", expected, writer.getDocStats().numDocs);
    writer.close();

    // make sure searching sees right # hits for term search
    IndexReader reader = DirectoryReader.open(dir);
    IndexSearcher searcher = newSearcher(reader);
    ScoreDoc[] hits = searcher.search(new TermQuery(new Term("content", "aaa")), 1000).scoreDocs;
    Document d = searcher.getIndexReader().storedFields().document(hits[0].doc);
    assertEquals("wrong first document", "0", d.get("id"));
    doTestHits(hits, 44, searcher.getIndexReader());

    if (nameVersion.major >= KNN_VECTOR_MIN_SUPPORTED_VERSION) {
      // make sure KNN search sees all hits (graph may not be used if k is big)
      assertKNNSearch(searcher, KNN_VECTOR, 1000, 44, "0");
      // make sure KNN search using HNSW graph sees newly added docs
      assertKNNSearch(
          searcher,
          new float[] {KNN_VECTOR[0], KNN_VECTOR[1], KNN_VECTOR[2] + 0.1f * 44},
          10,
          10,
          "44");
    }
    reader.close();

    // fully merge
    writer =
        new IndexWriter(
            dir,
            newIndexWriterConfig(new MockAnalyzer(random))
                .setOpenMode(OpenMode.APPEND)
                .setMergePolicy(newLogMergePolicy()));
    writer.forceMerge(1);
    writer.close();

    reader = DirectoryReader.open(dir);
    searcher = newSearcher(reader);
    // make sure searching sees right # hits fot term search
    hits = searcher.search(new TermQuery(new Term("content", "aaa")), 1000).scoreDocs;
    assertEquals("wrong number of hits", 44, hits.length);
    d = searcher.storedFields().document(hits[0].doc);
    doTestHits(hits, 44, searcher.getIndexReader());
    assertEquals("wrong first document", "0", d.get("id"));

    if (nameVersion.major >= KNN_VECTOR_MIN_SUPPORTED_VERSION) {
      // make sure KNN search sees all hits
      assertKNNSearch(searcher, KNN_VECTOR, 1000, 44, "0");
      // make sure KNN search using HNSW graph sees newly added docs
      assertKNNSearch(
          searcher,
          new float[] {KNN_VECTOR[0], KNN_VECTOR[1], KNN_VECTOR[2] + 0.1f * 44},
          10,
          10,
          "44");
    }
    reader.close();
  }

  public void changeIndexNoAdds(Random random, Directory dir, Version nameVersion)
      throws IOException {
    // make sure searching sees right # hits for term search
    DirectoryReader reader = DirectoryReader.open(dir);
    IndexSearcher searcher = newSearcher(reader);
    ScoreDoc[] hits = searcher.search(new TermQuery(new Term("content", "aaa")), 1000).scoreDocs;
    assertEquals("wrong number of hits", 34, hits.length);
    Document d = searcher.storedFields().document(hits[0].doc);
    assertEquals("wrong first document", "0", d.get("id"));

    if (nameVersion.major >= KNN_VECTOR_MIN_SUPPORTED_VERSION) {
      // make sure KNN search sees all hits
      assertKNNSearch(searcher, KNN_VECTOR, 1000, 34, "0");
      // make sure KNN search using HNSW graph retrieves correct results
      assertKNNSearch(searcher, KNN_VECTOR, 10, 10, "0");
    }
    reader.close();

    // fully merge
    IndexWriter writer =
        new IndexWriter(
            dir, newIndexWriterConfig(new MockAnalyzer(random)).setOpenMode(OpenMode.APPEND));
    writer.forceMerge(1);
    writer.close();

    reader = DirectoryReader.open(dir);
    searcher = newSearcher(reader);
    // make sure searching sees right # hits fot term search
    hits = searcher.search(new TermQuery(new Term("content", "aaa")), 1000).scoreDocs;
    assertEquals("wrong number of hits", 34, hits.length);
    doTestHits(hits, 34, searcher.getIndexReader());
    // make sure searching sees right # hits for KNN search
    if (nameVersion.major >= KNN_VECTOR_MIN_SUPPORTED_VERSION) {
      // make sure KNN search sees all hits
      assertKNNSearch(searcher, KNN_VECTOR, 1000, 34, "0");
      // make sure KNN search using HNSW graph retrieves correct results
      assertKNNSearch(searcher, KNN_VECTOR, 10, 10, "0");
    }
    reader.close();
  }

  public void createIndex(Directory dir, boolean doCFS, boolean fullyMerged) throws IOException {
    LogByteSizeMergePolicy mp = new LogByteSizeMergePolicy();
    mp.setNoCFSRatio(doCFS ? 1.0 : 0.0);
    mp.setMaxCFSSegmentSizeMB(Double.POSITIVE_INFINITY);
    // TODO: remove randomness
    IndexWriterConfig conf =
        new IndexWriterConfig(new MockAnalyzer(random()))
            .setMaxBufferedDocs(10)
            .setMergePolicy(NoMergePolicy.INSTANCE);
    IndexWriter writer = new IndexWriter(dir, conf);

    for (int i = 0; i < DOCS_COUNT; i++) {
      addDoc(writer, i);
    }
    assertEquals("wrong doc count", DOCS_COUNT, writer.getDocStats().maxDoc);
    if (fullyMerged) {
      writer.forceMerge(1);
    }
    writer.close();

    if (!fullyMerged) {
      // open fresh writer so we get no prx file in the added segment
      mp = new LogByteSizeMergePolicy();
      mp.setNoCFSRatio(doCFS ? 1.0 : 0.0);
      // TODO: remove randomness
      conf =
          new IndexWriterConfig(new MockAnalyzer(random()))
              .setMaxBufferedDocs(10)
              .setMergePolicy(NoMergePolicy.INSTANCE);
      writer = new IndexWriter(dir, conf);
      addNoProxDoc(writer);
      writer.close();

      conf =
          new IndexWriterConfig(new MockAnalyzer(random()))
              .setMaxBufferedDocs(10)
              .setMergePolicy(NoMergePolicy.INSTANCE);
      writer = new IndexWriter(dir, conf);
      Term searchTerm = new Term("id", String.valueOf(DELETED_ID));
      writer.deleteDocuments(searchTerm);
      writer.close();
    }
  }

  private void addDoc(IndexWriter writer, int id) throws IOException {
    Document doc = new Document();
    doc.add(new TextField("content", "aaa", Field.Store.NO));
    doc.add(new StringField("id", Integer.toString(id), Field.Store.YES));
    FieldType customType2 = new FieldType(TextField.TYPE_STORED);
    customType2.setStoreTermVectors(true);
    customType2.setStoreTermVectorPositions(true);
    customType2.setStoreTermVectorOffsets(true);
    doc.add(
        new Field(
            "autf8", "Lu\uD834\uDD1Ece\uD834\uDD60ne \u0000 \u2620 ab\ud917\udc17cd", customType2));
    doc.add(
        new Field(
            "utf8", "Lu\uD834\uDD1Ece\uD834\uDD60ne \u0000 \u2620 ab\ud917\udc17cd", customType2));
    doc.add(new Field("content2", "here is more content with aaa aaa aaa", customType2));
    doc.add(new Field("fie\u2C77ld", "field with non-ascii name", customType2));

    // add docvalues fields
    doc.add(new NumericDocValuesField("dvByte", (byte) id));
    byte[] bytes =
        new byte[] {(byte) (id >>> 24), (byte) (id >>> 16), (byte) (id >>> 8), (byte) id};
    BytesRef ref = new BytesRef(bytes);
    doc.add(new BinaryDocValuesField("dvBytesDerefFixed", ref));
    doc.add(new BinaryDocValuesField("dvBytesDerefVar", ref));
    doc.add(new SortedDocValuesField("dvBytesSortedFixed", ref));
    doc.add(new SortedDocValuesField("dvBytesSortedVar", ref));
    doc.add(new BinaryDocValuesField("dvBytesStraightFixed", ref));
    doc.add(new BinaryDocValuesField("dvBytesStraightVar", ref));
    doc.add(new DoubleDocValuesField("dvDouble", id));
    doc.add(new FloatDocValuesField("dvFloat", (float) id));
    doc.add(new NumericDocValuesField("dvInt", id));
    doc.add(new NumericDocValuesField("dvLong", id));
    doc.add(new NumericDocValuesField("dvPacked", id));
    doc.add(new NumericDocValuesField("dvShort", (short) id));
    doc.add(new SortedSetDocValuesField("dvSortedSet", ref));
    doc.add(new SortedNumericDocValuesField("dvSortedNumeric", id));

    doc.add(new IntPoint("intPoint1d", id));
    doc.add(new IntPoint("intPoint2d", id, 2 * id));
    doc.add(new FloatPoint("floatPoint1d", (float) id));
    doc.add(new FloatPoint("floatPoint2d", (float) id, (float) 2 * id));
    doc.add(new LongPoint("longPoint1d", id));
    doc.add(new LongPoint("longPoint2d", id, 2 * id));
    doc.add(new DoublePoint("doublePoint1d", id));
    doc.add(new DoublePoint("doublePoint2d", id, (double) 2 * id));
    doc.add(new BinaryPoint("binaryPoint1d", bytes));
    doc.add(new BinaryPoint("binaryPoint2d", bytes, bytes));

    // a field with both offsets and term vectors for a cross-check
    FieldType customType3 = new FieldType(TextField.TYPE_STORED);
    customType3.setStoreTermVectors(true);
    customType3.setStoreTermVectorPositions(true);
    customType3.setStoreTermVectorOffsets(true);
    customType3.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
    doc.add(new Field("content5", "here is more content with aaa aaa aaa", customType3));
    // a field that omits only positions
    FieldType customType4 = new FieldType(TextField.TYPE_STORED);
    customType4.setStoreTermVectors(true);
    customType4.setStoreTermVectorPositions(false);
    customType4.setStoreTermVectorOffsets(true);
    customType4.setIndexOptions(IndexOptions.DOCS_AND_FREQS);
    doc.add(new Field("content6", "here is more content with aaa aaa aaa", customType4));

    float[] vector = {KNN_VECTOR[0], KNN_VECTOR[1], KNN_VECTOR[2] + 0.1f * id};
    doc.add(new KnnFloatVectorField(KNN_VECTOR_FIELD, vector, KNN_VECTOR_FIELD_TYPE));

    // TODO:
    //   index different norms types via similarity (we use a random one currently?!)
    //   remove any analyzer randomness, explicitly add payloads for certain fields.
    writer.addDocument(doc);
  }

  private void addNoProxDoc(IndexWriter writer) throws IOException {
    Document doc = new Document();
    FieldType customType = new FieldType(TextField.TYPE_STORED);
    customType.setIndexOptions(IndexOptions.DOCS);
    Field f = new Field("content3", "aaa", customType);
    doc.add(f);
    FieldType customType2 = new FieldType();
    customType2.setStored(true);
    customType2.setIndexOptions(IndexOptions.DOCS);
    f = new Field("content4", "aaa", customType2);
    doc.add(f);
    writer.addDocument(doc);
  }

  private int countDocs(PostingsEnum docs) throws IOException {
    int count = 0;
    while ((docs.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
      count++;
    }
    return count;
  }

  // flex: test basics of TermsEnum api on non-flex index
  public void testNextIntoWrongField() throws Exception {
    for (String name : oldNames) {
      Directory dir = oldIndexDirs.get(name);
      IndexReader r = DirectoryReader.open(dir);
      TermsEnum terms = MultiTerms.getTerms(r, "content").iterator();
      BytesRef t = terms.next();
      assertNotNull(t);

      // content field only has term aaa:
      assertEquals("aaa", t.utf8ToString());
      assertNull(terms.next());

      BytesRef aaaTerm = new BytesRef("aaa");

      // should be found exactly
      assertEquals(TermsEnum.SeekStatus.FOUND, terms.seekCeil(aaaTerm));
      assertEquals(DOCS_COUNT, countDocs(TestUtil.docs(random(), terms, null, PostingsEnum.NONE)));
      assertNull(terms.next());

      // should hit end of field
      assertEquals(TermsEnum.SeekStatus.END, terms.seekCeil(new BytesRef("bbb")));
      assertNull(terms.next());

      // should seek to aaa
      assertEquals(TermsEnum.SeekStatus.NOT_FOUND, terms.seekCeil(new BytesRef("a")));
      assertTrue(terms.term().bytesEquals(aaaTerm));
      assertEquals(DOCS_COUNT, countDocs(TestUtil.docs(random(), terms, null, PostingsEnum.NONE)));
      assertNull(terms.next());

      assertEquals(TermsEnum.SeekStatus.FOUND, terms.seekCeil(aaaTerm));
      assertEquals(DOCS_COUNT, countDocs(TestUtil.docs(random(), terms, null, PostingsEnum.NONE)));
      assertNull(terms.next());

      r.close();
    }
  }

  /**
   * Test that we didn't forget to bump the current Constants.LUCENE_MAIN_VERSION. This is important
   * so that we can determine which version of lucene wrote the segment.
   */
  public void testOldVersions() throws Exception {
    // first create a little index with the current code and get the version
    Directory currentDir = newDirectory();
    RandomIndexWriter riw = new RandomIndexWriter(random(), currentDir);
    riw.addDocument(new Document());
    riw.close();
    DirectoryReader ir = DirectoryReader.open(currentDir);
    SegmentReader air = (SegmentReader) ir.leaves().get(0).reader();
    Version currentVersion = air.getSegmentInfo().info.getVersion();
    assertNotNull(currentVersion); // only 3.0 segments can have a null version
    ir.close();
    currentDir.close();

    // now check all the old indexes, their version should be < the current version
    for (String name : oldNames) {
      Directory dir = oldIndexDirs.get(name);
      DirectoryReader r = DirectoryReader.open(dir);
      for (LeafReaderContext context : r.leaves()) {
        air = (SegmentReader) context.reader();
        Version oldVersion = air.getSegmentInfo().info.getVersion();
        assertNotNull(oldVersion); // only 3.0 segments can have a null version
        assertTrue(
            "current Version.LATEST is <= an old index: did you forget to bump it?!",
            currentVersion.onOrAfter(oldVersion));
      }
      r.close();
    }
  }

  public void testIndexCreatedVersion() throws IOException {
    for (String name : oldNames) {
      Directory dir = oldIndexDirs.get(name);
      SegmentInfos infos = SegmentInfos.readLatestCommit(dir);
      // those indexes are created by a single version so we can
      // compare the commit version with the created version
      assertEquals(infos.getCommitLuceneVersion().major, infos.getIndexCreatedVersionMajor());
    }
  }

  public void testSegmentCommitInfoId() throws IOException {
    for (String name : oldNames) {
      Directory dir = oldIndexDirs.get(name);
      SegmentInfos infos = SegmentInfos.readLatestCommit(dir);
      for (SegmentCommitInfo info : infos) {
        if (info.info.getVersion().onOrAfter(Version.fromBits(8, 6, 0))) {
          assertNotNull(info.toString(), info.getId());
        } else {
          assertNull(info.toString(), info.getId());
        }
      }
    }
  }

  public void verifyUsesDefaultCodec(Directory dir, String name) throws IOException {
    DirectoryReader r = DirectoryReader.open(dir);
    for (LeafReaderContext context : r.leaves()) {
      SegmentReader air = (SegmentReader) context.reader();
      Codec codec = air.getSegmentInfo().info.getCodec();
      assertTrue(
          "codec used in "
              + name
              + " ("
              + codec.getName()
              + ") is not a default codec (does not begin with Lucene)",
          codec.getName().startsWith("Lucene"));
    }
    r.close();
  }

  public void testAllIndexesUseDefaultCodec() throws Exception {
    for (String name : oldNames) {
      Directory dir = oldIndexDirs.get(name);
      verifyUsesDefaultCodec(dir, name);
    }
  }

  private int checkAllSegmentsUpgraded(Directory dir, int indexCreatedVersion) throws IOException {
    final SegmentInfos infos = SegmentInfos.readLatestCommit(dir);
    if (VERBOSE) {
      System.out.println("checkAllSegmentsUpgraded: " + infos);
    }
    for (SegmentCommitInfo si : infos) {
      assertEquals(Version.LATEST, si.info.getVersion());
      assertNotNull(si.getId());
    }
    assertEquals(Version.LATEST, infos.getCommitLuceneVersion());
    assertEquals(indexCreatedVersion, infos.getIndexCreatedVersionMajor());
    return infos.size();
  }

  private int getNumberOfSegments(Directory dir) throws IOException {
    final SegmentInfos infos = SegmentInfos.readLatestCommit(dir);
    return infos.size();
  }

  public void testUpgradeOldIndex() throws Exception {
    List<String> names = new ArrayList<>(oldNames.length + oldSingleSegmentNames.length);
    names.addAll(Arrays.asList(oldNames));
    names.addAll(Arrays.asList(oldSingleSegmentNames));
    for (String name : names) {
      if (VERBOSE) {
        System.out.println("testUpgradeOldIndex: index=" + name);
      }
      Directory dir = newDirectory(oldIndexDirs.get(name));
      int indexCreatedVersion = SegmentInfos.readLatestCommit(dir).getIndexCreatedVersionMajor();

      newIndexUpgrader(dir).upgrade();

      checkAllSegmentsUpgraded(dir, indexCreatedVersion);

      dir.close();
    }
  }

  public void testIndexUpgraderCommandLineArgs() throws Exception {

    PrintStream savedSystemOut = System.out;
    System.setOut(new PrintStream(new ByteArrayOutputStream(), false, UTF_8));
    try {
      for (Map.Entry<String, Directory> entry : oldIndexDirs.entrySet()) {
        String name = entry.getKey();
        Directory origDir = entry.getValue();
        int indexCreatedVersion =
            SegmentInfos.readLatestCommit(origDir).getIndexCreatedVersionMajor();
        Path dir = createTempDir(name);
        try (FSDirectory fsDir = FSDirectory.open(dir)) {
          // beware that ExtraFS might add extraXXX files
          Set<String> extraFiles = Set.of(fsDir.listAll());
          for (String file : origDir.listAll()) {
            if (extraFiles.contains(file) == false) {
              fsDir.copyFrom(origDir, file, file, IOContext.DEFAULT);
            }
          }
        }

        String path = dir.toAbsolutePath().toString();

        List<String> args = new ArrayList<>();
        if (random().nextBoolean()) {
          args.add("-verbose");
        }
        if (random().nextBoolean()) {
          args.add("-delete-prior-commits");
        }
        if (random().nextBoolean()) {
          // TODO: need to better randomize this, but ...
          //  - LuceneTestCase.FS_DIRECTORIES is private
          //  - newFSDirectory returns BaseDirectoryWrapper
          //  - BaseDirectoryWrapper doesn't expose delegate
          Class<? extends FSDirectory> dirImpl = NIOFSDirectory.class;

          args.add("-dir-impl");
          args.add(dirImpl.getName());
        }
        args.add(path);

        IndexUpgrader.main(args.toArray(new String[0]));

        Directory upgradedDir = newFSDirectory(dir);
        try (upgradedDir) {
          checkAllSegmentsUpgraded(upgradedDir, indexCreatedVersion);
        }
      }
    } finally {
      System.setOut(savedSystemOut);
    }
  }

  public void testUpgradeOldSingleSegmentIndexWithAdditions() throws Exception {
    for (String name : oldSingleSegmentNames) {
      if (VERBOSE) {
        System.out.println("testUpgradeOldSingleSegmentIndexWithAdditions: index=" + name);
      }
      Directory dir = newDirectory(oldIndexDirs.get(name));
      assertEquals("Original index must be single segment", 1, getNumberOfSegments(dir));
      int indexCreatedVersion = SegmentInfos.readLatestCommit(dir).getIndexCreatedVersionMajor();

      // create a bunch of dummy segments
      int id = 40;
      Directory ramDir = new ByteBuffersDirectory();
      for (int i = 0; i < 3; i++) {
        // only use Log- or TieredMergePolicy, to make document addition predictable and not
        // suddenly merge:
        MergePolicy mp = random().nextBoolean() ? newLogMergePolicy() : newTieredMergePolicy();
        IndexWriterConfig iwc =
            new IndexWriterConfig(new MockAnalyzer(random())).setMergePolicy(mp);
        IndexWriter w = new IndexWriter(ramDir, iwc);
        // add few more docs:
        for (int j = 0; j < RANDOM_MULTIPLIER * random().nextInt(30); j++) {
          addDoc(w, id++);
        }
        try {
          w.commit();
        } finally {
          w.close();
        }
      }

      // add dummy segments (which are all in current
      // version) to single segment index
      MergePolicy mp = random().nextBoolean() ? newLogMergePolicy() : newTieredMergePolicy();
      IndexWriterConfig iwc = new IndexWriterConfig(null).setMergePolicy(mp);
      IndexWriter w = new IndexWriter(dir, iwc);
      w.addIndexes(ramDir);
      try (w) {
        w.commit();
      }

      // determine count of segments in modified index
      final int origSegCount = getNumberOfSegments(dir);

      // ensure there is only one commit
      assertEquals(1, DirectoryReader.listCommits(dir).size());
      newIndexUpgrader(dir).upgrade();

      final int segCount = checkAllSegmentsUpgraded(dir, indexCreatedVersion);
      assertEquals(
          "Index must still contain the same number of segments, as only one segment was upgraded and nothing else merged",
          origSegCount,
          segCount);

      dir.close();
    }
  }

  public static final String emptyIndex = "empty.9.0.0.zip";

  public void testUpgradeEmptyOldIndex() throws Exception {
    Path oldIndexDir = createTempDir("emptyIndex");
    TestUtil.unzip(getDataInputStream(emptyIndex), oldIndexDir);
    Directory dir = newFSDirectory(oldIndexDir);

    newIndexUpgrader(dir).upgrade();

    checkAllSegmentsUpgraded(dir, 9);

    dir.close();
  }

  public static final String moreTermsIndex = "moreterms.9.0.0.zip";

  public void testMoreTerms() throws Exception {
    Path oldIndexDir = createTempDir("moreterms");
    TestUtil.unzip(getDataInputStream(moreTermsIndex), oldIndexDir);
    Directory dir = newFSDirectory(oldIndexDir);
    DirectoryReader reader = DirectoryReader.open(dir);

    verifyUsesDefaultCodec(dir, moreTermsIndex);
    TestUtil.checkIndex(dir);
    searchExampleIndex(reader);

    reader.close();
    dir.close();
  }

  public static final String dvUpdatesIndex = "dvupdates.9.0.0.zip";

  private void assertNumericDocValues(LeafReader r, String f, String cf) throws IOException {
    NumericDocValues ndvf = r.getNumericDocValues(f);
    NumericDocValues ndvcf = r.getNumericDocValues(cf);
    for (int i = 0; i < r.maxDoc(); i++) {
      assertEquals(i, ndvcf.nextDoc());
      assertEquals(i, ndvf.nextDoc());
      assertEquals(ndvcf.longValue(), ndvf.longValue() * 2);
    }
  }

  private void assertBinaryDocValues(LeafReader r, String f, String cf) throws IOException {
    BinaryDocValues bdvf = r.getBinaryDocValues(f);
    BinaryDocValues bdvcf = r.getBinaryDocValues(cf);
    for (int i = 0; i < r.maxDoc(); i++) {
      assertEquals(i, bdvf.nextDoc());
      assertEquals(i, bdvcf.nextDoc());
      assertEquals(getValue(bdvcf), getValue(bdvf) * 2);
    }
  }

  private void verifyDocValues(Directory dir) throws IOException {
    DirectoryReader reader = DirectoryReader.open(dir);
    for (LeafReaderContext context : reader.leaves()) {
      LeafReader r = context.reader();
      assertNumericDocValues(r, "ndv1", "ndv1_c");
      assertNumericDocValues(r, "ndv2", "ndv2_c");
      assertBinaryDocValues(r, "bdv1", "bdv1_c");
      assertBinaryDocValues(r, "bdv2", "bdv2_c");
    }
    reader.close();
  }

  public void testDocValuesUpdates() throws Exception {
    Path oldIndexDir = createTempDir("dvupdates");
    TestUtil.unzip(getDataInputStream(dvUpdatesIndex), oldIndexDir);
    try (Directory dir = newFSDirectory(oldIndexDir)) {
      searchDocValuesUpdatesIndex(dir);
    }
  }

  private void searchDocValuesUpdatesIndex(Directory dir) throws IOException {
    verifyUsesDefaultCodec(dir, dvUpdatesIndex);
    verifyDocValues(dir);

    // update fields and verify index
    IndexWriterConfig conf = new IndexWriterConfig(new MockAnalyzer(random()));
    IndexWriter writer = new IndexWriter(dir, conf);
    updateNumeric(writer, "1", "ndv1", "ndv1_c", 300L);
    updateNumeric(writer, "1", "ndv2", "ndv2_c", 300L);
    updateBinary(writer, "1", "bdv1", "bdv1_c", 300L);
    updateBinary(writer, "1", "bdv2", "bdv2_c", 300L);

    writer.commit();
    verifyDocValues(dir);

    // merge all segments
    writer.forceMerge(1);
    writer.commit();
    verifyDocValues(dir);

    writer.close();
  }

  public void testDeletes() throws Exception {
    Path oldIndexDir = createTempDir("dvupdates");
    TestUtil.unzip(getDataInputStream(dvUpdatesIndex), oldIndexDir);
    Directory dir = newFSDirectory(oldIndexDir);
    verifyUsesDefaultCodec(dir, dvUpdatesIndex);

    IndexWriterConfig conf = new IndexWriterConfig(new MockAnalyzer(random()));
    IndexWriter writer = new IndexWriter(dir, conf);

    int maxDoc = writer.getDocStats().maxDoc;
    writer.deleteDocuments(new Term("id", "1"));
    if (random().nextBoolean()) {
      writer.commit();
    }

    writer.forceMerge(1);
    writer.commit();
    assertEquals(maxDoc - 1, writer.getDocStats().maxDoc);

    writer.close();
    dir.close();
  }

  public void testSoftDeletes() throws Exception {
    Path oldIndexDir = createTempDir("dvupdates");
    TestUtil.unzip(getDataInputStream(dvUpdatesIndex), oldIndexDir);
    Directory dir = newFSDirectory(oldIndexDir);
    verifyUsesDefaultCodec(dir, dvUpdatesIndex);
    IndexWriterConfig conf =
        new IndexWriterConfig(new MockAnalyzer(random())).setSoftDeletesField("__soft_delete");
    IndexWriter writer = new IndexWriter(dir, conf);
    int maxDoc = writer.getDocStats().maxDoc;
    writer.updateDocValues(new Term("id", "1"), new NumericDocValuesField("__soft_delete", 1));

    if (random().nextBoolean()) {
      writer.commit();
    }
    writer.forceMerge(1);
    writer.commit();
    assertEquals(maxDoc - 1, writer.getDocStats().maxDoc);
    writer.close();
    dir.close();
  }

  public void testDocValuesUpdatesWithNewField() throws Exception {
    Path oldIndexDir = createTempDir("dvupdates");
    TestUtil.unzip(getDataInputStream(dvUpdatesIndex), oldIndexDir);
    Directory dir = newFSDirectory(oldIndexDir);
    verifyUsesDefaultCodec(dir, dvUpdatesIndex);

    // update fields and verify index
    IndexWriterConfig conf = new IndexWriterConfig(new MockAnalyzer(random()));
    IndexWriter writer = new IndexWriter(dir, conf);
    // introduce a new field that we later update
    writer.addDocument(
        Arrays.asList(
            new StringField("id", "" + Integer.MAX_VALUE, Field.Store.NO),
            new NumericDocValuesField("new_numeric", 1),
            new BinaryDocValuesField("new_binary", toBytes(1))));
    writer.updateNumericDocValue(new Term("id", "1"), "new_numeric", 1);
    writer.updateBinaryDocValue(new Term("id", "1"), "new_binary", toBytes(1));

    writer.commit();
    Runnable assertDV =
        () -> {
          boolean found = false;
          try (DirectoryReader reader = DirectoryReader.open(dir)) {
            for (LeafReaderContext ctx : reader.leaves()) {
              LeafReader leafReader = ctx.reader();
              TermsEnum id = leafReader.terms("id").iterator();
              if (id.seekExact(new BytesRef("1"))) {
                PostingsEnum postings = id.postings(null, PostingsEnum.NONE);
                NumericDocValues numericDocValues = leafReader.getNumericDocValues("new_numeric");
                BinaryDocValues binaryDocValues = leafReader.getBinaryDocValues("new_binary");
                int doc;
                while ((doc = postings.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                  found = true;
                  assertTrue(binaryDocValues.advanceExact(doc));
                  assertTrue(numericDocValues.advanceExact(doc));
                  assertEquals(1, numericDocValues.longValue());
                  assertEquals(toBytes(1), binaryDocValues.binaryValue());
                }
              }
            }
          } catch (IOException e) {
            throw new AssertionError(e);
          }
          assertTrue(found);
        };
    assertDV.run();
    // merge all segments
    writer.forceMerge(1);
    writer.commit();
    assertDV.run();
    writer.close();
    dir.close();
  }

  // LUCENE-5907
  public void testUpgradeWithNRTReader() throws Exception {
    for (String name : oldNames) {
      Directory dir = newDirectory(oldIndexDirs.get(name));

      IndexWriter writer =
          new IndexWriter(
              dir, newIndexWriterConfig(new MockAnalyzer(random())).setOpenMode(OpenMode.APPEND));
      writer.addDocument(new Document());
      DirectoryReader r = DirectoryReader.open(writer);
      writer.commit();
      r.close();
      writer.forceMerge(1);
      writer.commit();
      writer.rollback();
      SegmentInfos.readLatestCommit(dir);
      dir.close();
    }
  }

  // LUCENE-5907
  public void testUpgradeThenMultipleCommits() throws Exception {
    for (String name : oldNames) {
      Directory dir = newDirectory(oldIndexDirs.get(name));

      IndexWriter writer =
          new IndexWriter(
              dir, newIndexWriterConfig(new MockAnalyzer(random())).setOpenMode(OpenMode.APPEND));
      writer.addDocument(new Document());
      writer.commit();
      writer.addDocument(new Document());
      writer.commit();
      writer.close();
      dir.close();
    }
  }

  public void testSortedIndex() throws Exception {
    for (String name : oldSortedNames) {
      Path path = createTempDir("sorted");
      InputStream resource = TestBackwardsCompatibility.class.getResourceAsStream(name + ".zip");
      assertNotNull("Sorted index index " + name + " not found", resource);
      TestUtil.unzip(resource, path);

      Directory dir = newFSDirectory(path);
      DirectoryReader reader = DirectoryReader.open(dir);

      assertEquals(1, reader.leaves().size());
      Sort sort = reader.leaves().get(0).reader().getMetaData().getSort();
      assertNotNull(sort);
      assertEquals("<long: \"dateDV\">!", sort.toString());

      // This will confirm the docs are really sorted
      TestUtil.checkIndex(dir);

      searchExampleIndex(reader);

      reader.close();
      dir.close();
    }
  }

  public void testSortedIndexAddDocBlocks() throws Exception {
    for (String name : oldSortedNames) {
      Path path = createTempDir("sorted");
      InputStream resource = TestBackwardsCompatibility.class.getResourceAsStream(name + ".zip");
      assertNotNull("Sorted index index " + name + " not found", resource);
      TestUtil.unzip(resource, path);

      try (Directory dir = newFSDirectory(path)) {
        final Sort sort;
        try (DirectoryReader reader = DirectoryReader.open(dir)) {
          assertEquals(1, reader.leaves().size());
          sort = reader.leaves().get(0).reader().getMetaData().getSort();
          assertNotNull(sort);
          searchExampleIndex(reader);
        }
        // open writer
        try (IndexWriter writer =
            new IndexWriter(
                dir,
                newIndexWriterConfig(new MockAnalyzer(random()))
                    .setOpenMode(OpenMode.APPEND)
                    .setIndexSort(sort)
                    .setMergePolicy(newLogMergePolicy()))) {
          // add 10 docs
          for (int i = 0; i < 10; i++) {
            Document child = new Document();
            child.add(new StringField("relation", "child", Field.Store.NO));
            child.add(new StringField("bid", "" + i, Field.Store.NO));
            child.add(new NumericDocValuesField("dateDV", i));
            Document parent = new Document();
            parent.add(new StringField("relation", "parent", Field.Store.NO));
            parent.add(new StringField("bid", "" + i, Field.Store.NO));
            parent.add(new NumericDocValuesField("dateDV", i));
            writer.addDocuments(Arrays.asList(child, child, parent));
            if (random().nextBoolean()) {
              writer.flush();
            }
          }
          if (random().nextBoolean()) {
            writer.forceMerge(1);
          }
          writer.commit();
          try (IndexReader reader = DirectoryReader.open(dir)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            for (int i = 0; i < 10; i++) {
              TopDocs children =
                  searcher.search(
                      new BooleanQuery.Builder()
                          .add(
                              new TermQuery(new Term("relation", "child")),
                              BooleanClause.Occur.MUST)
                          .add(new TermQuery(new Term("bid", "" + i)), BooleanClause.Occur.MUST)
                          .build(),
                      2);
              TopDocs parents =
                  searcher.search(
                      new BooleanQuery.Builder()
                          .add(
                              new TermQuery(new Term("relation", "parent")),
                              BooleanClause.Occur.MUST)
                          .add(new TermQuery(new Term("bid", "" + i)), BooleanClause.Occur.MUST)
                          .build(),
                      2);
              assertEquals(2, children.totalHits.value);
              assertEquals(1, parents.totalHits.value);
              // make sure it's sorted
              assertEquals(children.scoreDocs[0].doc + 1, children.scoreDocs[1].doc);
              assertEquals(children.scoreDocs[1].doc + 1, parents.scoreDocs[0].doc);
            }
          }
        }
        // This will confirm the docs are really sorted
        TestUtil.checkIndex(dir);
      }
    }
  }

  private void searchExampleIndex(DirectoryReader reader) throws IOException {
    IndexSearcher searcher = newSearcher(reader);

    TopDocs topDocs = searcher.search(new FieldExistsQuery("titleTokenized"), 10);
    assertEquals(50, topDocs.totalHits.value);

    topDocs = searcher.search(new FieldExistsQuery("titleDV"), 10);
    assertEquals(50, topDocs.totalHits.value);

    topDocs = searcher.search(new TermQuery(new Term("body", "ja")), 10);
    assertTrue(topDocs.totalHits.value > 0);

    topDocs =
        searcher.search(
            IntPoint.newRangeQuery("docid_int", 42, 44),
            10,
            new Sort(new SortField("docid_intDV", SortField.Type.INT)));
    assertEquals(3, topDocs.totalHits.value);
    assertEquals(3, topDocs.scoreDocs.length);
    assertEquals(42, ((FieldDoc) topDocs.scoreDocs[0]).fields[0]);
    assertEquals(43, ((FieldDoc) topDocs.scoreDocs[1]).fields[0]);
    assertEquals(44, ((FieldDoc) topDocs.scoreDocs[2]).fields[0]);

    topDocs = searcher.search(new TermQuery(new Term("body", "the")), 5);
    assertTrue(topDocs.totalHits.value > 0);

    topDocs =
        searcher.search(
            new MatchAllDocsQuery(), 5, new Sort(new SortField("dateDV", SortField.Type.LONG)));
    assertEquals(50, topDocs.totalHits.value);
    assertEquals(5, topDocs.scoreDocs.length);
    long firstDate = (Long) ((FieldDoc) topDocs.scoreDocs[0]).fields[0];
    long lastDate = (Long) ((FieldDoc) topDocs.scoreDocs[4]).fields[0];
    assertTrue(firstDate <= lastDate);
  }

  static long getValue(BinaryDocValues bdv) throws IOException {
    BytesRef term = bdv.binaryValue();
    int idx = term.offset;
    byte b = term.bytes[idx++];
    long value = b & 0x7FL;
    for (int shift = 7; (b & 0x80L) != 0; shift += 7) {
      b = term.bytes[idx++];
      value |= (b & 0x7FL) << shift;
    }
    return value;
  }

  // encodes a long into a BytesRef as VLong so that we get varying number of bytes when we update
  static BytesRef toBytes(long value) {
    BytesRef bytes = new BytesRef(10); // negative longs may take 10 bytes
    while ((value & ~0x7FL) != 0L) {
      bytes.bytes[bytes.length++] = (byte) ((value & 0x7FL) | 0x80L);
      value >>>= 7;
    }
    bytes.bytes[bytes.length++] = (byte) value;
    return bytes;
  }

  public void testFailOpenOldIndex() throws IOException {
    for (String name : oldNames) {
      Directory directory = oldIndexDirs.get(name);
      IndexCommit commit = DirectoryReader.listCommits(directory).get(0);
      IndexFormatTooOldException ex =
          expectThrows(
              IndexFormatTooOldException.class,
              () -> StandardDirectoryReader.open(commit, Version.LATEST.major, null));
      assertTrue(
          ex.getMessage()
              .contains(
                  "only supports reading from version " + Version.LATEST.major + " upwards."));
      // now open with allowed min version
      StandardDirectoryReader.open(commit, Version.MIN_SUPPORTED_MAJOR, null).close();
    }
  }

  // #12895: test on a carefully crafted 9.8.0 index (from a small contiguous subset
  // of wikibigall unique terms) that shows the read-time exception of
  // IntersectTermsEnum (used by WildcardQuery)
  public void testWildcardQueryExceptions990() throws IOException {
    Path path = createTempDir("12895");

    String name = "index.12895.9.8.0.zip";
    InputStream resource = TestBackwardsCompatibility.class.getResourceAsStream(name);
    assertNotNull("missing zip file to reproduce #12895", resource);
    TestUtil.unzip(resource, path);

    try (Directory dir = newFSDirectory(path);
        DirectoryReader reader = DirectoryReader.open(dir)) {
      IndexSearcher searcher = new IndexSearcher(reader);

      searcher.count(new WildcardQuery(new Term("field", "*qx*")));
    }
  }

  @Nightly
  public void testReadNMinusTwoCommit() throws IOException {
    for (String name : binarySupportedNames) {
      Path oldIndexDir = createTempDir(name);
      TestUtil.unzip(getDataInputStream("unsupported." + name + ".zip"), oldIndexDir);
      try (BaseDirectoryWrapper dir = newFSDirectory(oldIndexDir)) {
        IndexCommit commit = DirectoryReader.listCommits(dir).get(0);
        StandardDirectoryReader.open(commit, MIN_BINARY_SUPPORTED_MAJOR, null).close();
      }
    }
  }

  @Nightly
  public void testReadNMinusTwoSegmentInfos() throws IOException {
    for (String name : binarySupportedNames) {
      Path oldIndexDir = createTempDir(name);
      TestUtil.unzip(getDataInputStream("unsupported." + name + ".zip"), oldIndexDir);
      try (BaseDirectoryWrapper dir = newFSDirectory(oldIndexDir)) {
        expectThrows(
            IndexFormatTooOldException.class,
            () -> SegmentInfos.readLatestCommit(dir, Version.MIN_SUPPORTED_MAJOR));
        SegmentInfos.readLatestCommit(dir, MIN_BINARY_SUPPORTED_MAJOR);
      }
    }
  }

  public void testOpenModeAndCreatedVersion() throws IOException {
    for (String name : oldNames) {
      Directory dir = newDirectory(oldIndexDirs.get(name));
      int majorVersion = SegmentInfos.readLatestCommit(dir).getIndexCreatedVersionMajor();
      if (majorVersion != Version.MIN_SUPPORTED_MAJOR && majorVersion != Version.LATEST.major) {
        fail(
            "expected one of: ["
                + Version.MIN_SUPPORTED_MAJOR
                + ", "
                + Version.LATEST.major
                + "] but got: "
                + majorVersion);
      }
      for (OpenMode openMode : OpenMode.values()) {
        Directory tmpDir = newDirectory(dir);
        IndexWriter w = new IndexWriter(tmpDir, newIndexWriterConfig().setOpenMode(openMode));
        w.commit();
        w.close();
        switch (openMode) {
          case CREATE:
            assertEquals(
                Version.LATEST.major,
                SegmentInfos.readLatestCommit(tmpDir).getIndexCreatedVersionMajor());
            break;
          case APPEND:
          case CREATE_OR_APPEND:
          default:
            assertEquals(
                majorVersion, SegmentInfos.readLatestCommit(tmpDir).getIndexCreatedVersionMajor());
        }
        tmpDir.close();
      }
      dir.close();
    }
  }
}
