/*
 * Copyright 2013 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package opennlp.addons.geoentitylinker;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.queryparser.classic.ParseException;

import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.util.Version;
import opennlp.tools.entitylinker.EntityLinkerProperties;

/**
 *
 * Searches Gazateers stored in a MMapDirectory Lucene index
 */
public class GazateerSearcher {

  private double scoreCutoff = .75;
  private Directory geonamesIndex;//= new MMapDirectory(new File(indexloc));
  private IndexReader geonamesReader;// = DirectoryReader.open(geonamesIndex);
  private IndexSearcher geonamesSearcher;// = new IndexSearcher(geonamesReader);
  private Analyzer geonamesAnalyzer;
  //usgs US gazateer
  private Directory usgsIndex;//= new MMapDirectory(new File(indexloc));
  private IndexReader usgsReader;// = DirectoryReader.open(geonamesIndex);
  private IndexSearcher usgsSearcher;// = new IndexSearcher(geonamesReader);
  private Analyzer usgsAnalyzer;
  private EntityLinkerProperties properties;

  public GazateerSearcher(EntityLinkerProperties properties) throws Exception {
    this.properties = properties;
    init();
  }

  /**
   *
   * @param searchString the named entity to look up in the lucene index
   * @param rowsReturned how many rows to allow lucene to return
   * @param code         the country code
   * @param properties   the entitylinker.properties file that states where the
   *                     lucene indexes are
   * @return
   */
  public ArrayList<GazateerEntry> geonamesFind(String searchString, int rowsReturned, String code) {
    ArrayList<GazateerEntry> linkedData = new ArrayList<>();
    String luceneQueryString = "";
    try {
      /**
       * build the search string Sometimes no country context is found. In this
       * case the code variable will be an empty string
       */
      luceneQueryString = !code.equals("")
              ? "FULL_NAME_ND_RO:" + searchString.toLowerCase().trim() + " AND CC1:" + code.toLowerCase() + "^1000"
              : "FULL_NAME_ND_RO:" + searchString.toLowerCase().trim();
      /**
       * check the cache and go no further if the records already exist
       */
      ArrayList<GazateerEntry> get = GazateerSearchCache.get(luceneQueryString);
      if (get != null) {

        return get;
      }


      QueryParser parser = new QueryParser(Version.LUCENE_45, luceneQueryString, geonamesAnalyzer);
      Query q = parser.parse(luceneQueryString);

      TopDocs search = geonamesSearcher.search(q, rowsReturned);
      double maxScore = (double) search.getMaxScore();

      for (int i = 0; i < search.scoreDocs.length; ++i) {
        GazateerEntry entry = new GazateerEntry();
        int docId = search.scoreDocs[i].doc;
        double sc = search.scoreDocs[i].score;

        entry.getScoreMap().put("lucene", sc);

        entry.getScoreMap().put("rawlucene", sc);
        entry.setIndexID(docId + "");
        entry.setSource("geonames");

        Document d = geonamesSearcher.doc(docId);
        List<IndexableField> fields = d.getFields();
        for (int idx = 0; idx < fields.size(); idx++) {
          String value = d.get(fields.get(idx).name());
          value = value.toLowerCase();
          /**
           * these positions map to the required fields in the gaz TODO: allow a
           * configurable list of columns that map to the GazateerEntry fields,
           * then users would be able to plug in any gazateer they have (if they
           * build a lucene index out of it)
           */
          switch (idx) {
            case 1:
              entry.setItemID(value);
              break;
            case 3:
              entry.setLatitude(Double.valueOf(value));
              break;
            case 4:
              entry.setLongitude(Double.valueOf(value));
              break;
            case 10:
              entry.setItemType(value);
              break;
            case 12:
              entry.setItemParentID(value);
              break;
            case 23:
              entry.setItemName(value);
              break;
          }
          entry.getIndexData().put(fields.get(idx).name(), value);
        }
        //only keep it if the country code is a match. even when the code is passed in as a weighted condition, there is no == equiv in lucene
        if (entry.getItemParentID().toLowerCase().equals(code.toLowerCase())) {
          if (!linkedData.contains(entry)) {
            linkedData.add(entry);
          }
        }
      }
      if (!linkedData.isEmpty()) {
        normalize(linkedData, 0d, maxScore);
        prune(linkedData);
      }
    } catch (IOException | ParseException ex) {
      System.err.println(ex);
    }
    /**
     * add the records to the cache for this query
     */
    GazateerSearchCache.put(luceneQueryString, linkedData);
    return linkedData;
  }

  /**
   * Looks up the name in the USGS gazateer, after checking the cache
   *
   * @param searchString the nameed entity to look up in the lucene index
   * @param rowsReturned how many rows to allow lucene to return
   *
   * @param properties   properties file that states where the lucene indexes
   * @return
   */
  public ArrayList<GazateerEntry> usgsFind(String searchString, int rowsReturned) {
    ArrayList<GazateerEntry> linkedData = new ArrayList<>();
    String luceneQueryString = "FEATURE_NAME:" + searchString.toLowerCase().trim() + " OR MAP_NAME: " + searchString.toLowerCase().trim();
    try {


      /**
       * hit the cache
       */
      ArrayList<GazateerEntry> get = GazateerSearchCache.get(luceneQueryString);
      if (get != null) {
        //if the name is already there, return the list of cavhed results
        return get;
      }
      QueryParser parser = new QueryParser(Version.LUCENE_45, luceneQueryString, usgsAnalyzer);
      Query q = parser.parse(luceneQueryString);

      TopDocs search = usgsSearcher.search(q, rowsReturned);
      double maxScore = (double) search.getMaxScore();

      for (int i = 0; i < search.scoreDocs.length; i++) {
        GazateerEntry entry = new GazateerEntry();
        int docId = search.scoreDocs[i].doc;
        double sc = search.scoreDocs[i].score;
        //keep track of the min score for normalization

        entry.getScoreMap().put("lucene", sc);
        entry.getScoreMap().put("rawlucene", sc);
        entry.setIndexID(docId + "");
        entry.setSource("usgs");
        entry.setItemParentID("us");
        Document d = usgsSearcher.doc(docId);
        List<IndexableField> fields = d.getFields();
        for (int idx = 0; idx < fields.size(); idx++) {
          String value = d.get(fields.get(idx).name());
          value = value.toLowerCase();
          switch (idx) {
            case 0:
              entry.setItemID(value);
              break;
            case 1:
              entry.setItemName(value);
              break;
            case 2:
              entry.setItemType(value);
              break;
            case 9:
              entry.setLatitude(Double.valueOf(value));
              break;
            case 10:
              entry.setLongitude(Double.valueOf(value));
              break;
          }
          entry.getIndexData().put(fields.get(idx).name(), value);
        }
        if (!linkedData.contains(entry)) {
          linkedData.add(entry);
        }
      }
      if (!linkedData.isEmpty()) {
        normalize(linkedData, 0d, maxScore);
        prune(linkedData);
      }
    } catch (IOException | ParseException ex) {
      System.err.println(ex);
    }
    /**
     * add the records to the cache for this query
     */
    GazateerSearchCache.put(luceneQueryString, linkedData);
    return linkedData;
  }

  private void normalize(ArrayList<GazateerEntry> linkedData, Double minScore, Double maxScore) {
    for (GazateerEntry gazateerEntry : linkedData) {

      double luceneScore = gazateerEntry.getScoreMap().get("lucene");
      luceneScore = normalize(luceneScore, minScore, maxScore);
      luceneScore = luceneScore > 1.0 ? 1.0 : luceneScore;
      luceneScore = (luceneScore == Double.NaN) ? 0.001 : luceneScore;
      gazateerEntry.getScoreMap().put("lucene", luceneScore);
    }
  }

  /**
   * gets rid of entries that are below the score thresh
   *
   * @param linkedData
   */
  private void prune(ArrayList<GazateerEntry> linkedData) {
    for (Iterator<GazateerEntry> itr = linkedData.iterator(); itr.hasNext();) {
      GazateerEntry ge = itr.next();
      /**
       * throw away anything under the configured score thresh
       */
      if (ge.getScoreMap().get("lucene") < scoreCutoff) {
        itr.remove();
      }
    }
  }

  /**
   * normalizes the different levenstein scores returned from the query into a
   *
   * @param valueToNormalize the raw score
   * @param minimum          the min of the range of scores
   * @param maximum          the max of the range
   * @return the normed score
   */
  private Double normalize(Double valueToNormalize, Double minimum, Double maximum) {
    Double d = (double) ((1 - 0) * (valueToNormalize - minimum)) / (maximum - minimum) + 0;
    d = d == null ? 0d : d;
    return d;
  }

  private void init() throws Exception {
    if (usgsIndex == null) {
      String indexloc = properties.getProperty("opennlp.geoentitylinker.gaz.usgs", "");
      if (indexloc.equals("")) {
        System.out.println("USGS Gaz location not found");

      }
      String cutoff = properties.getProperty("opennlp.geoentitylinker.gaz.lucenescore.min", ".75");
      scoreCutoff = Double.valueOf(cutoff);
      usgsIndex = new MMapDirectory(new File(indexloc));
      usgsReader = DirectoryReader.open(usgsIndex);
      usgsSearcher = new IndexSearcher(usgsReader);
      usgsAnalyzer = new StandardAnalyzer(Version.LUCENE_45);
    }
    if (geonamesIndex == null) {
      String indexloc = properties.getProperty("opennlp.geoentitylinker.gaz.geonames", "");
      if (indexloc.equals("")) {
        System.out.println("Geonames Gaz location not found");

      }
      String cutoff = properties.getProperty("opennlp.geoentitylinker.gaz.lucenescore.min", String.valueOf(scoreCutoff));
      scoreCutoff = Double.valueOf(cutoff);
      geonamesIndex = new MMapDirectory(new File(indexloc));
      geonamesReader = DirectoryReader.open(geonamesIndex);
      geonamesSearcher = new IndexSearcher(geonamesReader);
      //TODO: a language code switch statement should be employed here at some point
      geonamesAnalyzer = new StandardAnalyzer(Version.LUCENE_45);

    }
  }
}