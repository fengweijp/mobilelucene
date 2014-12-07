package org.apache.lucene.spatial.vector;

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

import org.apache.lucene.document.Document;
import org.apache.lucene.document.FieldTypes;
import org.apache.lucene.queries.function.FunctionQuery;
import org.apache.lucene.queries.function.ValueSource;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.FilteredQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryWrapperFilter;
import org.apache.lucene.spatial.SpatialStrategy;
import org.apache.lucene.spatial.query.SpatialArgs;
import org.apache.lucene.spatial.query.SpatialOperation;
import org.apache.lucene.spatial.query.UnsupportedSpatialOperation;
import org.apache.lucene.spatial.util.CachingDoubleValueSource;
import org.apache.lucene.spatial.util.ValueSourceFilter;
import com.spatial4j.core.context.SpatialContext;
import com.spatial4j.core.shape.Circle;
import com.spatial4j.core.shape.Point;
import com.spatial4j.core.shape.Rectangle;
import com.spatial4j.core.shape.Shape;

/**
 * Simple {@link SpatialStrategy} which represents Points in two numeric {@link
 * DoubleField}s.  The Strategy's best feature is decent distance sort.
 *
 * <p>
 * <b>Characteristics:</b>
 * <p>
 * <ul>
 * <li>Only indexes points; just one per field value.</li>
 * <li>Can query by a rectangle or circle.</li>
 * <li>{@link
 * org.apache.lucene.spatial.query.SpatialOperation#Intersects} and {@link
 * SpatialOperation#IsWithin} is supported.</li>
 * <li>Uses the FieldCache for
 * {@link #makeDistanceValueSource(com.spatial4j.core.shape.Point)} and for
 * searching with a Circle.</li>
 * </ul>
 *
 * <p>
 * <b>Implementation:</b>
 * <p>
 * This is a simple Strategy.  Search works with range queries on
 * an x and y pair of fields.  A Circle query does the same bbox query but adds a
 * ValueSource filter on
 * {@link #makeDistanceValueSource(com.spatial4j.core.shape.Point)}.
 * <p />
 * One performance shortcoming with this strategy is that a scenario involving
 * both a search using a Circle and sort will result in calculations for the
 * spatial distance being done twice -- once for the filter and second for the
 * sort.
 *
 * @lucene.experimental
 */
public class PointVectorStrategy extends SpatialStrategy {

  public static final String SUFFIX_X = "__x";
  public static final String SUFFIX_Y = "__y";

  private final String fieldNameX;
  private final String fieldNameY;

  public int precisionStep = 8; // same as solr default

  public PointVectorStrategy(SpatialContext ctx, String fieldNamePrefix) {
    super(ctx, fieldNamePrefix);
    this.fieldNameX = fieldNamePrefix+SUFFIX_X;
    this.fieldNameY = fieldNamePrefix+SUFFIX_Y;
  }

  public void setPrecisionStep( int p ) {
    precisionStep = p;
    if (precisionStep<=0 || precisionStep>=64)
      precisionStep=Integer.MAX_VALUE;
  }

  String getFieldNameX() {
    return fieldNameX;
  }

  String getFieldNameY() {
    return fieldNameY;
  }

  @Override
  public void addFields(Document doc, Shape shape) {
    if (shape instanceof Point) {
      addFields(doc, (Point) shape);
      return;
    }
    throw new UnsupportedOperationException("Can only index Point, not " + shape);
  }

  /** @see #createIndexableFields(com.spatial4j.core.shape.Shape) */
  public void addFields(Document doc, Point point) {
    doc.addDouble(fieldNameX, point.getX());
    doc.addDouble(fieldNameY, point.getY());
  }

  @Override
  public ValueSource makeDistanceValueSource(Point queryPoint, double multiplier) {
    return new DistanceValueSource(this, queryPoint, multiplier);
  }

  @Override
  public Filter makeFilter(FieldTypes fieldTypes, SpatialArgs args) {
    //unwrap the CSQ from makeQuery
    ConstantScoreQuery csq = makeQuery(fieldTypes, args);
    Filter filter = csq.getFilter();
    if (filter != null)
      return filter;
    else
      return new QueryWrapperFilter(csq.getQuery());
  }

  @Override
  public ConstantScoreQuery makeQuery(FieldTypes fieldTypes, SpatialArgs args) {
    if(! SpatialOperation.is( args.getOperation(),
        SpatialOperation.Intersects,
        SpatialOperation.IsWithin ))
      throw new UnsupportedSpatialOperation(args.getOperation());
    Shape shape = args.getShape();
    if (shape instanceof Rectangle) {
      Rectangle bbox = (Rectangle) shape;
      return new ConstantScoreQuery(makeWithin(fieldTypes, bbox));
    } else if (shape instanceof Circle) {
      Circle circle = (Circle)shape;
      Rectangle bbox = circle.getBoundingBox();
      ValueSourceFilter vsf = new ValueSourceFilter(
          new QueryWrapperFilter(makeWithin(fieldTypes, bbox)),
          makeDistanceValueSource(circle.getCenter()),
          0,
          circle.getRadius() );
      return new ConstantScoreQuery(vsf);
    } else {
      throw new UnsupportedOperationException("Only Rectangles and Circles are currently supported, " +
          "found [" + shape.getClass() + "]");//TODO
    }
  }

  //TODO this is basically old code that hasn't been verified well and should probably be removed
  public Query makeQueryDistanceScore(FieldTypes fieldTypes, SpatialArgs args) {
    // For starters, just limit the bbox
    Shape shape = args.getShape();
    if (!(shape instanceof Rectangle || shape instanceof Circle)) {
      throw new UnsupportedOperationException("Only Rectangles and Circles are currently supported, " +
          "found [" + shape.getClass() + "]");//TODO
    }

    Rectangle bbox = shape.getBoundingBox();

    if (bbox.getCrossesDateLine()) {
      throw new UnsupportedOperationException( "Crossing dateline not yet supported" );
    }

    ValueSource valueSource = null;

    Query spatial = null;
    SpatialOperation op = args.getOperation();

    if( SpatialOperation.is( op,
        SpatialOperation.BBoxWithin,
        SpatialOperation.BBoxIntersects ) ) {
        spatial = makeWithin(fieldTypes, bbox);
    }
    else if( SpatialOperation.is( op,
      SpatialOperation.Intersects,
      SpatialOperation.IsWithin ) ) {
      spatial = makeWithin(fieldTypes, bbox);
      if( args.getShape() instanceof Circle) {
        Circle circle = (Circle)args.getShape();

        // Make the ValueSource
        valueSource = makeDistanceValueSource(shape.getCenter());

        ValueSourceFilter vsf = new ValueSourceFilter(
            new QueryWrapperFilter( spatial ), valueSource, 0, circle.getRadius() );

        spatial = new FilteredQuery( new MatchAllDocsQuery(), vsf );
      }
    }
    else if( op == SpatialOperation.IsDisjointTo ) {
      spatial =  makeDisjoint(fieldTypes, bbox);
    }

    if( spatial == null ) {
      throw new UnsupportedSpatialOperation(args.getOperation());
    }

    if( valueSource != null ) {
      valueSource = new CachingDoubleValueSource(valueSource);
    }
    else {
      valueSource = makeDistanceValueSource(shape.getCenter());
    }
    Query spatialRankingQuery = new FunctionQuery(valueSource);
    BooleanQuery bq = new BooleanQuery();
    bq.add(spatial,BooleanClause.Occur.MUST);
    bq.add(spatialRankingQuery,BooleanClause.Occur.MUST);
    return bq;
  }

  /**
   * Constructs a query to retrieve documents that fully contain the input envelope.
   */
  private Query makeWithin(FieldTypes fieldTypes, Rectangle bbox) {
    BooleanQuery bq = new BooleanQuery();
    BooleanClause.Occur MUST = BooleanClause.Occur.MUST;
    if (bbox.getCrossesDateLine()) {
      //use null as performance trick since no data will be beyond the world bounds
      bq.add(rangeQuery(fieldTypes, fieldNameX, null/*-180*/, bbox.getMaxX()), BooleanClause.Occur.SHOULD );
      bq.add(rangeQuery(fieldTypes, fieldNameX, bbox.getMinX(), null/*+180*/), BooleanClause.Occur.SHOULD );
      bq.setMinimumNumberShouldMatch(1);//must match at least one of the SHOULD
    } else {
      bq.add(rangeQuery(fieldTypes, fieldNameX, bbox.getMinX(), bbox.getMaxX()), MUST);
    }
    bq.add(rangeQuery(fieldTypes, fieldNameY, bbox.getMinY(), bbox.getMaxY()), MUST);

    return bq;
  }

  private Query rangeQuery(FieldTypes fieldTypes, String fieldName, Double min, Double max) {
    return new ConstantScoreQuery(fieldTypes.newDoubleRangeFilter(
        fieldName,
        min,
        true,
        max,
        true));//inclusive
  }

  /**
   * Constructs a query to retrieve documents that fully contain the input envelope.
   */
  private Query makeDisjoint(FieldTypes fieldTypes, Rectangle bbox) {
    if (bbox.getCrossesDateLine())
      throw new UnsupportedOperationException("makeDisjoint doesn't handle dateline cross");
    Query qX = rangeQuery(fieldTypes, fieldNameX, bbox.getMinX(), bbox.getMaxX());
    Query qY = rangeQuery(fieldTypes, fieldNameY, bbox.getMinY(), bbox.getMaxY());

    BooleanQuery bq = new BooleanQuery();
    bq.add(qX,BooleanClause.Occur.MUST_NOT);
    bq.add(qY,BooleanClause.Occur.MUST_NOT);
    return bq;
  }

}




