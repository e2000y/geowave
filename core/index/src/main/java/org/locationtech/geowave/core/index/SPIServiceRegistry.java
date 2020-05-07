/**
 * Copyright (c) 2013-2020 Contributors to the Eclipse Foundation
 *
 * <p> See the NOTICE file distributed with this work for additional information regarding copyright
 * ownership. All rights reserved. This program and the accompanying materials are made available
 * under the terms of the Apache License, Version 2.0 which accompanies this distribution and is
 * available at http://www.apache.org/licenses/LICENSE-2.0.txt
 */
package org.locationtech.geowave.core.index;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.geotools.util.factory.FactoryRegistry;
import org.geotools.util.factory.GeoTools;
import org.apache.commons.collections4.IteratorUtils;

/**
 * Compensate for VFSClassloader's failure to discovery SPI registered classes (used by JBOSS and
 * Accumulo).
 *
 * <p> To Use:
 *
 * <p> (1) Register class loaders:
 *
 * <p> (2) Look up SPI providers:
 *
 * <p> final Iterator<FieldSerializationProviderSpi> serializationProviders = new
 * SPIServiceRegistry(FieldSerializationProviderSpi.class).load(
 * FieldSerializationProviderSpi.class);
 */
public class SPIServiceRegistry extends FactoryRegistry {

  private static final Logger LOGGER = LoggerFactory.getLogger(SPIServiceRegistry.class);

  @SuppressWarnings("unchecked")
  public SPIServiceRegistry(final Class<?> category) {
    super(category);
  }

  public SPIServiceRegistry(final Iterator<Class<?>> categories) {
    super(IteratorUtils.toList(categories));
  }

  public static void registerClassLoader(final ClassLoader loader) {
    GeoTools.addClassLoader(loader);
  }

  public <T> Iterator<T> load(final Class<T> service) {
    return getFactories(service, null, null).iterator();
  }
}
