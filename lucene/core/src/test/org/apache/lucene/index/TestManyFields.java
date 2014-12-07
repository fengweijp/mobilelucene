package org.apache.lucene.index;

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

import org.apache.lucene.analysis.MockAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.FieldTypes;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.LuceneTestCase;

/** Test that creates way, way, way too many fields */
public class TestManyFields extends LuceneTestCase {
  
  public void testManyFields() throws IOException {
    Directory dir = newDirectory();
    IndexWriter writer  = new IndexWriter(dir, newIndexWriterConfig(new MockAnalyzer(random()))
                                                 .setMaxBufferedDocs(10));
    for(int j=0;j<100;j++) {
      Document doc = writer.newDocument();
      doc.addAtom("a"+j, "aaa" + j);
      doc.addAtom("b"+j, "aaa" + j);
      doc.addAtom("c"+j, "aaa" + j);
      doc.addAtom("d"+j, "aaa");
      doc.addAtom("e"+j, "aaa");
      doc.addAtom("f"+j, "aaa");
      writer.addDocument(doc);
    }
    writer.close();

    IndexReader reader = DirectoryReader.open(dir);
    assertEquals(100, reader.maxDoc());
    assertEquals(100, reader.numDocs());
    for(int j=0;j<100;j++) {
      assertEquals(1, reader.docFreq(new Term("a"+j, "aaa"+j)));
      assertEquals(1, reader.docFreq(new Term("b"+j, "aaa"+j)));
      assertEquals(1, reader.docFreq(new Term("c"+j, "aaa"+j)));
      assertEquals(1, reader.docFreq(new Term("d"+j, "aaa")));
      assertEquals(1, reader.docFreq(new Term("e"+j, "aaa")));
      assertEquals(1, reader.docFreq(new Term("f"+j, "aaa")));
    }
    reader.close();
    dir.close();
  }

  public void testDiverseDocs() throws IOException {
    Directory dir = newDirectory();
    IndexWriter writer  = new IndexWriter(dir, newIndexWriterConfig(new MockAnalyzer(random()))
                                                 .setRAMBufferSizeMB(0.5));
    FieldTypes fieldTypes = writer.getFieldTypes();
    fieldTypes.setMultiValued("field");

    int n = atLeast(1);
    for(int i=0;i<n;i++) {
      // First, docs where every term is unique (heavy on
      // Posting instances)
      for(int j=0;j<100;j++) {
        Document doc = writer.newDocument();
        for(int k=0;k<100;k++) {
          doc.addLargeText("field", Integer.toString(random().nextInt()));
        }
        writer.addDocument(doc);
      }

      // Next, many single term docs where only one term
      // occurs (heavy on byte blocks)
      for(int j=0;j<100;j++) {
        Document doc = writer.newDocument();
        doc.addLargeText("field", "aaa aaa aaa aaa aaa aaa aaa aaa aaa aaa");
        writer.addDocument(doc);
      }

      // Next, many single term docs where only one term
      // occurs but the terms are very long (heavy on
      // char[] arrays)
      for(int j=0;j<100;j++) {
        StringBuilder b = new StringBuilder();
        String x = Integer.toString(j) + ".";
        for(int k=0;k<1000;k++) {
          b.append(x);
        }
        String longTerm = b.toString();

        Document doc = writer.newDocument();
        doc.addLargeText("field", longTerm);
        writer.addDocument(doc);
      }
    }
    writer.close();

    IndexReader reader = DirectoryReader.open(dir);
    IndexSearcher searcher = newSearcher(reader);
    int totalHits = searcher.search(new TermQuery(new Term("field", "aaa")), null, 1).totalHits;
    assertEquals(n*100, totalHits);
    reader.close();

    dir.close();
  }
  
  // LUCENE-4398
  public void testRotatingFieldNames() throws Exception {
    Directory dir = newFSDirectory(createTempDir("TestIndexWriter.testChangingFields"));
    IndexWriterConfig iwc = new IndexWriterConfig(new MockAnalyzer(random()));
    iwc.setRAMBufferSizeMB(0.2);
    iwc.setMaxBufferedDocs(-1);
    IndexWriter w = new IndexWriter(dir, iwc);
    FieldTypes fieldTypes = w.getFieldTypes();
    int upto = 0;

    int firstDocCount = -1;
    for(int iter=0;iter<10;iter++) {
      final int startFlushCount = w.getFlushCount();
      int docCount = 0;
      while(w.getFlushCount() == startFlushCount) {
        Document doc = w.newDocument();
        for(int i=0;i<10;i++) {
          String fieldName = "field" + (upto++);
          fieldTypes.disableNorms(fieldName);
          doc.addLargeText(fieldName, "content");
        }
        w.addDocument(doc);
        docCount++;
      }

      if (VERBOSE) {
        System.out.println("TEST: iter=" + iter + " flushed after docCount=" + docCount);
      }

      if (iter == 0) {
        firstDocCount = docCount;
      }

      assertTrue("flushed after too few docs: first segment flushed at docCount=" + firstDocCount + ", but current segment flushed after docCount=" + docCount + "; iter=" + iter, ((float) docCount) / firstDocCount > 0.9);

      if (upto > 5000) {
        // Start re-using field names after a while
        // ... important because otherwise we can OOME due
        // to too many FieldInfo instances.
        upto = 0;
      }
    }
    w.close();
    dir.close();
  }
}
