/**************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *************************************************************************/

package com.alexkli.osgi.troubleshoot.impl;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks which packages/classes dynamically register/unregister services.
 */
public class ServiceOriginTracker implements ServiceListener {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final Map<String, Set<String>> origins = new ConcurrentHashMap<String, Set<String>>();

    private static String className = ServiceOriginTracker.class.getName();

    public ServiceOriginTracker(BundleContext bundleContext) {
        log.info("Start tracking services");
        bundleContext.addServiceListener(this);
    }

    public void stop(BundleContext bundleContext) {
        bundleContext.removeServiceListener(this);
        log.info("Stopped tracking services");
    }

    public Set<String> getOrigins(String serviceInterface) {
        Set<String> values = origins.get(serviceInterface);
        if (values == null) {
            return Collections.emptySet();
        }
        return values;
    }

    @Override
    public void serviceChanged(ServiceEvent event) {
        if (event.getType() == ServiceEvent.UNREGISTERING) {
            ServiceReference<?> ref = event.getServiceReference();

            String[] interfaces = (String[]) ref.getProperty(Constants.OBJECTCLASS);
            String origin = getOrigin();
            synchronized (origins) {
                for (String anInterface : interfaces) {
                    log.info("{} -> {}", anInterface, origin);
                    addToMultiSetMap(origins, anInterface, origin);
                }
            }
        }
    }

    private String getOrigin() {
        StackTraceElement[] stack = new Throwable().getStackTrace();
//        for (StackTraceElement stackTraceElement : stack) {
//            log.info("> {}", stackTraceElement);
//        }
        int pos = 0;
        pos = skip(className, stack, pos);
        pos = skip("org.apache.felix.framework.", stack, pos);
        if (pos >= stack.length) {
            return null;
        }
        String origin = stack[pos].getClassName();
        if (origin.startsWith("org.apache.felix.scr.")) {
            return "scr";
//            pos = skip("org.apache.felix.scr.", stack, pos);
//            if (pos >= stack.length) {
//                return null;
//            }
//            origin = stack[pos].getClassName();
        }
        return origin;
    }

    private int skip(String prefix, StackTraceElement[] stack, int pos) {
        for (int i = pos; i < stack.length; i++) {
            if (!stack[i].getClassName().startsWith(prefix)) {
                return i;
            }
        }
        return stack.length;
    }

    private <K, V> void addToMultiSetMap(Map<K, Set<V>> map, K key, V value) {
        Set<V> components = map.get(key);
        if (components == null) {
            components = new HashSet<V>();
            map.put(key, components);
        }
        components.add(value);
    }
}
