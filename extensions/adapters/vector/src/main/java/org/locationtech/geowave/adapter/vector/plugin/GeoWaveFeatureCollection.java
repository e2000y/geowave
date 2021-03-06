/**
 * Copyright (c) 2013-2020 Contributors to the Eclipse Foundation
 *
 * <p> See the NOTICE file distributed with this work for additional information regarding copyright
 * ownership. All rights reserved. This program and the accompanying materials are made available
 * under the terms of the Apache License, Version 2.0 which accompanies this distribution and is
 * available at http://www.apache.org/licenses/LICENSE-2.0.txt
 */
package org.locationtech.geowave.adapter.vector.plugin;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import org.geotools.data.DataUtilities;
import org.geotools.data.FeatureReader;
import org.geotools.data.Query;
import org.geotools.data.store.DataFeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.filter.spatial.BBOXImpl;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.locationtech.geowave.adapter.vector.render.DistributedRenderOptions;
import org.locationtech.geowave.adapter.vector.render.DistributedRenderResult;
import org.locationtech.geowave.core.geotime.store.query.TemporalConstraintsSet;
import org.locationtech.geowave.core.geotime.store.query.api.VectorStatisticsQueryBuilder;
import org.locationtech.geowave.core.geotime.store.statistics.BoundingBoxDataStatistics;
import org.locationtech.geowave.core.geotime.util.ExtractGeometryFilterVisitor;
import org.locationtech.geowave.core.geotime.util.ExtractGeometryFilterVisitorResult;
import org.locationtech.geowave.core.geotime.util.ExtractTimeFilterVisitor;
import org.locationtech.geowave.core.geotime.util.GeometryUtils;
import org.locationtech.geowave.core.store.CloseableIterator;
import org.locationtech.geowave.core.store.adapter.statistics.CountDataStatistics;
import org.locationtech.geowave.core.store.adapter.statistics.InternalDataStatistics;
import org.locationtech.geowave.core.store.adapter.statistics.StatisticsId;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.filter.Filter;
import org.opengis.filter.expression.Expression;
import org.opengis.geometry.BoundingBox;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.operation.TransformException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is a helper for the GeoWave GeoTools data store. It represents a collection of feature
 * data by encapsulating a GeoWave reader and a query object in order to open the appropriate cursor
 * to iterate over data. It uses Keys within the Query hints to determine whether to perform special
 * purpose queries such as decimation or distributed rendering.
 */
public class GeoWaveFeatureCollection extends DataFeatureCollection {
  private static final Logger LOGGER = LoggerFactory.getLogger(GeoWaveFeatureCollection.class);
  private final GeoWaveFeatureReader reader;
  private CloseableIterator<SimpleFeature> featureCursor;
  private final Query query;
  private static SimpleFeatureType distributedRenderFeatureType;

  public GeoWaveFeatureCollection(final GeoWaveFeatureReader reader, final Query query) {
    this.reader = reader;
    this.query =
        validateQuery(GeoWaveFeatureCollection.getSchema(reader, query).getTypeName(), query);
  }

  @Override
  public int getCount() {
    if (query.getFilter().equals(Filter.INCLUDE)) {
      // GEOWAVE-60 optimization
      final Map<StatisticsId, InternalDataStatistics<SimpleFeature, ?, ?>> statsMap =
          reader.getTransaction().getDataStatistics();
      final StatisticsId id = CountDataStatistics.STATS_TYPE.newBuilder().build().getId();
      if (statsMap.containsKey(id)) {
        final CountDataStatistics stats = (CountDataStatistics) statsMap.get(id);
        if ((stats != null) && stats.isSet()) {
          return (int) stats.getCount();
        }
      }
    } else if (query.getFilter().equals(Filter.EXCLUDE)) {
      return 0;
    }

    QueryConstraints constraints;
    try {
      constraints = getQueryConstraints();

      return (int) reader.getCountInternal(
          constraints.jtsBounds,
          constraints.timeBounds,
          getFilter(query),
          constraints.limit);
    } catch (TransformException | FactoryException e) {

      LOGGER.warn("Unable to transform geometry, can't get count", e);
    }
    // fallback
    return 0;
  }

  @Override
  public ReferencedEnvelope getBounds() {

    double minx = Double.MAX_VALUE, maxx = -Double.MAX_VALUE, miny = Double.MAX_VALUE,
        maxy = -Double.MAX_VALUE;
    try {
      // GEOWAVE-60 optimization
      final Map<StatisticsId, InternalDataStatistics<SimpleFeature, ?, ?>> statsMap =
          reader.getTransaction().getDataStatistics();
      final StatisticsId statId =
          VectorStatisticsQueryBuilder.newBuilder().factory().bbox().fieldName(
              reader.getFeatureType().getGeometryDescriptor().getLocalName()).build().getId();
      if (statsMap.containsKey(statId)) {
        final BoundingBoxDataStatistics<SimpleFeature, ?> stats =
            (BoundingBoxDataStatistics<SimpleFeature, ?>) statsMap.get(statId);
        return new ReferencedEnvelope(
            stats.getMinX(),
            stats.getMaxX(),
            stats.getMinY(),
            stats.getMaxY(),
            reader.getFeatureType().getCoordinateReferenceSystem());
      }
      final Iterator<SimpleFeature> iterator = openIterator();
      if (!iterator.hasNext()) {
        return null;
      }
      while (iterator.hasNext()) {
        final BoundingBox bbox = iterator.next().getBounds();
        minx = Math.min(bbox.getMinX(), minx);
        maxx = Math.max(bbox.getMaxX(), maxx);
        miny = Math.min(bbox.getMinY(), miny);
        maxy = Math.max(bbox.getMaxY(), maxy);
      }
      close(iterator);
    } catch (final Exception e) {
      LOGGER.warn("Error calculating bounds", e);
      return new ReferencedEnvelope(-180, 180, -90, 90, GeometryUtils.getDefaultCRS());
    }
    return new ReferencedEnvelope(minx, maxx, miny, maxy, GeometryUtils.getDefaultCRS());
  }

  @Override
  public SimpleFeatureType getSchema() {
    if (isDistributedRenderQuery()) {
      return getDistributedRenderFeatureType();
    }
    return reader.getFeatureType();
  }

  public static synchronized SimpleFeatureType getDistributedRenderFeatureType() {
    if (distributedRenderFeatureType == null) {
      distributedRenderFeatureType = createDistributedRenderFeatureType();
    }
    return distributedRenderFeatureType;
  }

  private static SimpleFeatureType createDistributedRenderFeatureType() {
    final SimpleFeatureTypeBuilder typeBuilder = new SimpleFeatureTypeBuilder();
    typeBuilder.setName("distributed_render");
    typeBuilder.add("result", DistributedRenderResult.class);
    typeBuilder.add("options", DistributedRenderOptions.class);
    return typeBuilder.buildFeatureType();
  }

  protected boolean isDistributedRenderQuery() {
    return GeoWaveFeatureCollection.isDistributedRenderQuery(query);
  }

  protected static final boolean isDistributedRenderQuery(final Query query) {
    return query.getHints().containsKey(DistributedRenderProcess.OPTIONS);
  }

  private static SimpleFeatureType getSchema(final GeoWaveFeatureReader reader, final Query query) {
    if (GeoWaveFeatureCollection.isDistributedRenderQuery(query)) {
      return getDistributedRenderFeatureType();
    }
    return reader.getComponents().getAdapter().getFeatureType();
  }

  private Filter getFilter(final Query query) {
    final Filter filter = query.getFilter();
    if (filter instanceof BBOXImpl) {
      final BBOXImpl bbox = ((BBOXImpl) filter);
      final Expression propName = bbox.getExpression1();
      if ((propName == null) || (propName == Expression.NIL)) {
        bbox.setPropertyName(getSchema(reader, query).getGeometryDescriptor().getLocalName());
      }
    }
    return filter;
  }

  protected QueryConstraints getQueryConstraints() throws TransformException, FactoryException {
    final ReferencedEnvelope referencedEnvelope = getEnvelope(query);
    final Geometry jtsBounds = getBBox(query, referencedEnvelope);
    final TemporalConstraintsSet timeBounds = getBoundedTime(query);
    Integer limit = getLimit(query);
    final Integer startIndex = getStartIndex(query);

    // limit becomes a 'soft' constraint since GeoServer will inforce
    // the limit
    final Long max =
        (limit != null) ? limit.longValue() + (startIndex == null ? 0 : startIndex.longValue())
            : null;
    // limit only used if less than an integer max value.
    limit = ((max != null) && (max.longValue() < Integer.MAX_VALUE)) ? max.intValue() : null;
    return new QueryConstraints(jtsBounds, timeBounds, referencedEnvelope, limit);
  }

  @Override
  protected Iterator<SimpleFeature> openIterator() {
    try {
      return openIterator(getQueryConstraints());

    } catch (TransformException | FactoryException e) {
      LOGGER.warn("Unable to transform geometry", e);
    }
    return featureCursor;
  }

  private Iterator<SimpleFeature> openIterator(final QueryConstraints contraints) {
    if (query.getFilter() == Filter.EXCLUDE) {
      featureCursor = reader.getNoData();
    } else if (isDistributedRenderQuery()) {
      featureCursor =
          reader.renderData(
              contraints.jtsBounds,
              contraints.timeBounds,
              getFilter(query),
              contraints.limit,
              (DistributedRenderOptions) query.getHints().get(DistributedRenderProcess.OPTIONS));
    } else if (query.getHints().containsKey(SubsampleProcess.OUTPUT_WIDTH)
        && query.getHints().containsKey(SubsampleProcess.OUTPUT_HEIGHT)
        && query.getHints().containsKey(SubsampleProcess.OUTPUT_BBOX)) {
      double pixelSize = 1;
      if (query.getHints().containsKey(SubsampleProcess.PIXEL_SIZE)) {
        pixelSize = (Double) query.getHints().get(SubsampleProcess.PIXEL_SIZE);
      }
      featureCursor =
          reader.getData(
              contraints.jtsBounds,
              contraints.timeBounds,
              (Integer) query.getHints().get(SubsampleProcess.OUTPUT_WIDTH),
              (Integer) query.getHints().get(SubsampleProcess.OUTPUT_HEIGHT),
              pixelSize,
              getFilter(query),
              contraints.referencedEnvelope,
              contraints.limit);

    } else {
      // get the data within the bounding box
      featureCursor =
          reader.getData(
              contraints.jtsBounds,
              contraints.timeBounds,
              getFilter(query),
              contraints.limit);
    }
    return featureCursor;
  }

  private ReferencedEnvelope getEnvelope(final Query query)
      throws TransformException, FactoryException {
    if (query.getHints().containsKey(SubsampleProcess.OUTPUT_BBOX)) {
      return ((ReferencedEnvelope) query.getHints().get(SubsampleProcess.OUTPUT_BBOX)).transform(
          reader.getFeatureType().getCoordinateReferenceSystem(),
          true);
    }
    return null;
  }

  private Geometry getBBox(final Query query, final ReferencedEnvelope envelope) {
    if (envelope != null) {
      return new GeometryFactory().toGeometry(envelope);
    }
    final String geomAtrributeName =
        reader.getComponents().getAdapter().getFeatureType().getGeometryDescriptor().getLocalName();
    final ExtractGeometryFilterVisitorResult geoAndCompareOp =
        ExtractGeometryFilterVisitor.getConstraints(
            query.getFilter(),
            reader.getComponents().getAdapter().getFeatureType().getCoordinateReferenceSystem(),
            geomAtrributeName);
    if (geoAndCompareOp == null) {
      return reader.clipIndexedBBOXConstraints(null);
    } else {
      return reader.clipIndexedBBOXConstraints(geoAndCompareOp.getGeometry());
    }
  }

  private Query validateQuery(final String typeName, final Query query) {
    return query == null ? new Query(typeName, Filter.EXCLUDE) : query;
  }

  private Integer getStartIndex(final Query query) {
    return query.getStartIndex();
  }

  private Integer getLimit(final Query query) {
    if (!query.isMaxFeaturesUnlimited() && (query.getMaxFeatures() >= 0)) {
      return query.getMaxFeatures();
    }
    return null;
  }

  @Override
  public void accepts(
      final org.opengis.feature.FeatureVisitor visitor,
      final org.opengis.util.ProgressListener progress) throws IOException {
    if (!GeoWaveGTPluginUtils.accepts(
        visitor,
        progress,
        reader.getFeatureType(),
        reader.getTransaction().getDataStatistics())) {
      DataUtilities.visit(this, visitor, progress);
    }
  }

  /**
   * @param query the query
   * @return the temporal constraints of the query
   */
  protected TemporalConstraintsSet getBoundedTime(final Query query) {
    if (query == null) {
      return null;
    }
    final TemporalConstraintsSet constraints =
        new ExtractTimeFilterVisitor(
            reader.getComponents().getAdapter().getTimeDescriptors()).getConstraints(query);

    return constraints.isEmpty() ? constraints : reader.clipIndexedTemporalConstraints(constraints);
  }

  @Override
  public FeatureReader<SimpleFeatureType, SimpleFeature> reader() {
    return reader;
  }

  @Override
  protected void closeIterator(final Iterator<SimpleFeature> close) {
    featureCursor.close();
  }

  public Iterator<SimpleFeature> getOpenIterator() {
    return featureCursor;
  }

  @Override
  public void close(final FeatureIterator<SimpleFeature> iterator) {
    featureCursor = null;
    super.close(iterator);
  }

  @Override
  public boolean isEmpty() {
    try {
      return !reader.hasNext();
    } catch (final IOException e) {
      LOGGER.warn("Error checking reader", e);
    }
    return true;
  }

  private static class QueryConstraints {
    Geometry jtsBounds;
    TemporalConstraintsSet timeBounds;
    ReferencedEnvelope referencedEnvelope;
    Integer limit;

    public QueryConstraints(
        final Geometry jtsBounds,
        final TemporalConstraintsSet timeBounds,
        final ReferencedEnvelope referencedEnvelope,
        final Integer limit) {
      super();
      this.jtsBounds = jtsBounds;
      this.timeBounds = timeBounds;
      this.referencedEnvelope = referencedEnvelope;
      this.limit = limit;
    }
  }
}
