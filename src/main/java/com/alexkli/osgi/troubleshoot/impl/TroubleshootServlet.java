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

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.felix.webconsole.AbstractWebConsolePlugin;
import org.apache.felix.webconsole.WebConsoleConstants;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.Version;
import org.osgi.framework.VersionRange;
import org.osgi.service.component.runtime.ServiceComponentRuntime;
import org.osgi.service.component.runtime.dto.ComponentConfigurationDTO;
import org.osgi.service.component.runtime.dto.ComponentDescriptionDTO;
import org.osgi.service.component.runtime.dto.ReferenceDTO;
import org.osgi.service.component.runtime.dto.SatisfiedReferenceDTO;
import org.osgi.service.packageadmin.ExportedPackage;
import org.osgi.service.packageadmin.PackageAdmin;

import com.alexkli.osgi.troubleshoot.impl.utils.Clause;
import com.alexkli.osgi.troubleshoot.impl.utils.Parser;

/**
 * Web console view that helps troubleshooting unresolved bundles and co.
 */
@Component
@Service(value = { Servlet.class })
@Properties({
    @Property(name=Constants.SERVICE_DESCRIPTION,       value="Web Console OSGi Troubleshoot Plugin"),
    @Property(name=WebConsoleConstants.PLUGIN_LABEL,    value=TroubleshootServlet.LABEL),
    @Property(name=WebConsoleConstants.PLUGIN_TITLE,    value=TroubleshootServlet.TITLE),
    @Property(name=WebConsoleConstants.PLUGIN_CATEGORY, value=TroubleshootServlet.CATEGORY)
})
@SuppressWarnings("serial")
public class TroubleshootServlet extends AbstractWebConsolePlugin {

    public static final String LABEL = "troubleshoot";
    public static final String TITLE = "Troubleshoot";
    public static final String CATEGORY = "OSGi";

    @Reference
    private PackageAdmin packageAdmin;

    @Reference
    private ServiceComponentRuntime scr;

    @Override
    public String getLabel() {
        return LABEL;
    }

    @Override
    public String getTitle() {
        return TITLE;
    }

    @Override
    public String getCategory() {
        return CATEGORY;
    }

    @Override
    protected void renderContent(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
        PrintWriter out = res.getWriter();

        out.println("<script>$(window).load(function(){\n" +
            "    $('.toggle').click(function(e){\n" +
            "        $(this).find('.toggle-content').toggle();\n" +
            "    });\n" +
            "});</script>");

        final Bundle[] bundles = getBundleContext().getBundles();

        handleBundles(req, res, bundles);

        out.println("<br>");
        out.println("<br>");
        out.println("<br>");

        handleServices(req, res);
    }

    // -----------------------------------< bundles >----------

    private void handleBundles(HttpServletRequest req, HttpServletResponse res, Bundle[] bundles) throws IOException {
        PrintWriter out = res.getWriter();

        out.println("<p class=\"statline ui-state-highlight\">");
        out.println(getBundleStatusLine(bundles));
        out.println("</p>");

        final List<Bundle> problematicBundles = getProblematicBundles(bundles);

        if (problematicBundles.isEmpty()) {
            out.println("All bundles ok.");
            return;
        }

        out.println("<b>Inactive Bundles</b>");
        out.println("<div>");

        final String bundlesUrl = req.getAttribute(WebConsoleConstants.ATTR_APP_ROOT) + "/bundles";

        for (Bundle bundle : problematicBundles) {
            out.println(getDetailLink(bundle, bundlesUrl));
            out.println(" ");
            out.println(getStatusString(bundle));
            out.println("<br>");

            ExportedPackage[] allExports = packageAdmin.getExportedPackages((Bundle) null);
            // multimap - same package can be exported in multiple versions
            Map<String, List<ExportedPackage>> globalExportMap = new HashMap<String, List<ExportedPackage>>();
            for (int j = 0; j < allExports.length; j++) {
                ExportedPackage exportedPackage = allExports[j];
                List<ExportedPackage> values = globalExportMap.get(exportedPackage.getName());
                if (values == null) {
                    values = new ArrayList<ExportedPackage>();
                    globalExportMap.put(exportedPackage.getName(), values);
                }
                values.add(exportedPackage);
            }

            Dictionary dict = bundle.getHeaders();

            // go through imports
            // - other bundle might not be resolved
            // - something else exports it, but in another (older) version
            // - nothing exports it

            String importHeader = (String) dict.get(Constants.IMPORT_PACKAGE);
            Clause[] imports = Parser.parseHeader(importHeader);
            if (imports != null) {

                for (Clause importPkg : imports) {
                    if (isOptional(importPkg)) {
                        continue;
                    }
                    if (isOwnPackage(bundle, importPkg.getName())) {
                        continue;
                    }

                    String name = importPkg.getName();
                    List<ExportedPackage> matchingExports = globalExportMap.get(name);
                    if (matchingExports != null) {
                        boolean satisfied = false;
                        for (ExportedPackage exported : matchingExports) {
                            if (isSatisfied(importPkg, exported)) {
                                satisfied = true;

                                Bundle exportingBundle = exported.getExportingBundle();
                                if (isInactive(exportingBundle)) {
                                    // not an actual issue, just a chain of dependencies not resolving
                                    out.print("- dependency not active: ");
                                    out.print(getDetailLink(exportingBundle, bundlesUrl));
                                    out.print(" ");
                                    out.println(getStatusString(exportingBundle));
                                    out.print(" (importing ");
                                    out.print(name);
                                    out.print(")");
                                    out.println("<br>");
                                }
                                break;
                            }
                        }
                        if (!satisfied) {
                            // here we have export candidates, but in a different version
                            out.print("<span class=\"ui-state-error-text\">");

                            String prefix = "";
                            if (matchingExports.size() > 1) {
                                prefix = "candidate ";
                            }
                            // common case
                            if (matchingExports.size() == 1) {
                                for (ExportedPackage export : matchingExports) {
                                    Version exportVersion = export.getVersion();
                                    String versionAttr = importPkg.getAttribute(Constants.VERSION_ATTRIBUTE);
                                    VersionRange importRange = new VersionRange(versionAttr);

                                    out.print("- ");
                                    out.print(prefix);
                                    if ((importRange.getLeftType() == VersionRange.LEFT_CLOSED && exportVersion.compareTo(importRange.getLeft()) < 0)
                                        || (importRange.getLeftType() == VersionRange.LEFT_OPEN && exportVersion.compareTo(importRange.getLeft()) <= 0)) {
                                        out.print("dependency too old: ");
                                    } else if ((importRange.getRightType() == VersionRange.RIGHT_CLOSED && exportVersion.compareTo(importRange.getRight()) > 0)
                                        || (importRange.getRightType() == VersionRange.RIGHT_OPEN && exportVersion.compareTo(importRange.getRight()) >= 0)) {
                                        out.print("dependency too new: ");
                                    } else {
                                        out.print("dependency with different version: ");
                                    }

                                    out.print(getDetailLink(export.getExportingBundle(), bundlesUrl));
                                    out.print(" (importing ");
                                    out.print(name);
                                    out.print(" ");
                                    out.print(versionAttr);
                                    out.print(" but found ");
                                    out.print(exportVersion.toString());
                                    out.print(")");
                                }
                            }
                            out.print("</span>");
                            out.println("<br>");
                        }
                    } else {
                        // not found at all, bundle missing
                        out.print("<span class=\"ui-state-error-text\">");
                        out.print("- not exported by any bundle: ");
                        out.println(name);
                        out.print("</span>");
                        out.println("<br>");
                    }
                }
            }
            out.println("<br>");
        }
        out.println("</div>");
    }

    /** Check if this bundle exports this package */
    private boolean isOwnPackage(Bundle bundle, String packageName) {
        String path = packageName.replace( '.', '/' );
        return bundle.getEntry( path ) != null;
    }

    private String getDetailLink(Bundle bundle, String bundlesUrl) {
        return "<a href='" + bundlesUrl + '/' + bundle.getBundleId() + "'>" +
                   bundle.getSymbolicName() + " (" + bundle.getBundleId() + ")" +
               "</a>";
    }

    private boolean isSatisfied( Clause imported, ExportedPackage exported )
    {
        if ( imported.getName().equals( exported.getName() ) )
        {
            String versionAttr = imported.getAttribute( Constants.VERSION_ATTRIBUTE );
            if ( versionAttr == null )
            {
                // no specific version required, this export surely satisfies it
                return true;
            }

            VersionRange required = new VersionRange(versionAttr);
            return required.includes( exported.getVersion() );
        }

        // no this export does not satisfy the import
        return false;
    }

    private boolean isOptional(Clause clause) {
        String directive = clause.getDirective(Constants.RESOLUTION_DIRECTIVE);
        return Constants.RESOLUTION_OPTIONAL.equals(directive);
    }

    private List<Bundle> getProblematicBundles(Bundle[] bundles) {
        List<Bundle> problemBundles = new ArrayList<Bundle>();
        for (Bundle bundle : bundles) {
            if (isInactive(bundle)) {
                problemBundles.add(bundle);
            }
        }
        return problemBundles;
    }

    private boolean isInactive(Bundle bundle) {
        if (isFragmentBundle(bundle)) {
            return bundle.getState() != Bundle.RESOLVED;
        } else {
            return bundle.getState() != Bundle.ACTIVE;
        }
    }

    private boolean isFragmentBundle(Bundle bundle )
    {
        // Workaround for FELIX-3670
        if ( bundle.getState() == Bundle.UNINSTALLED )
        {
            return bundle.getHeaders().get( Constants.FRAGMENT_HOST ) != null;
        }

        return getPackageAdmin().getBundleType( bundle ) == PackageAdmin.BUNDLE_TYPE_FRAGMENT;
    }

    private String getStatusString(final Bundle bundle )
    {
        switch ( bundle.getState() )
        {
            case Bundle.INSTALLED:
                return "Installed";
            case Bundle.RESOLVED:
                if ( isFragmentBundle(bundle) )
                {
                    return "Fragment";
                }
                return "Resolved";
            case Bundle.STARTING:
                return "Starting";
            case Bundle.ACTIVE:
                return "Active";
            case Bundle.STOPPING:
                return "Stopping";
            case Bundle.UNINSTALLED:
                return "Uninstalled";
            default:
                return "Unknown: " + bundle.getState();
        }
    }

    private String getBundleStatusLine(final Bundle[] bundles)
    {
        int active = 0, installed = 0, resolved = 0, fragments = 0;
        for (Bundle bundle : bundles) {
            switch (bundle.getState()) {
                case Bundle.ACTIVE:
                    active++;
                    break;
                case Bundle.INSTALLED:
                    installed++;
                    break;
                case Bundle.RESOLVED:
                    if (isFragmentBundle(bundle)) {
                        fragments++;
                    } else {
                        resolved++;
                    }
                    break;
            }
        }
        final StringBuffer buffer = new StringBuffer();
        buffer.append("Bundle information: ");
        appendBundleInfoCount(buffer, "in total", bundles.length);
        if ( active == bundles.length || active + fragments == bundles.length )
        {
            buffer.append(" - all ");
            appendBundleInfoCount(buffer, "active.", bundles.length);
        }
        else
        {
            if ( active != 0 )
            {
                buffer.append(", ");
                appendBundleInfoCount(buffer, "active", active);
            }
            if ( fragments != 0 )
            {
                buffer.append(", ");
                appendBundleInfoCount(buffer, "active fragments", fragments);
            }
            if ( resolved != 0 )
            {
                buffer.append(", ");
                appendBundleInfoCount(buffer, "resolved", resolved);
            }
            if ( installed != 0 )
            {
                buffer.append(", ");
                appendBundleInfoCount(buffer, "installed", installed);
            }
            buffer.append('.');
        }
        return buffer.toString();
    }

    private void appendBundleInfoCount( final StringBuffer buf, String msg, int count ) {
        buf.append(count);
        buf.append(" bundle");
        if ( count != 1 )
            buf.append( 's' );
        buf.append(' ');
        buf.append(msg);
    }

    private PackageAdmin getPackageAdmin() {
        return packageAdmin;
    }

    // -----------------------------------< services / components >----------

    private void handleServices(HttpServletRequest req, HttpServletResponse res) throws IOException {
        PrintWriter out = res.getWriter();

        final Collection<ComponentDescriptionDTO> allComponents = scr.getComponentDescriptionDTOs();

        ServiceReference<?>[] allServiceReferences = null;
        try {
            allServiceReferences = getBundleContext().getAllServiceReferences(null, null);
        } catch (InvalidSyntaxException ignore) {
            // filter is null
        }

        out.println("<p class=\"statline ui-state-highlight\">");
        out.println(getServiceStatusLine(allComponents, allServiceReferences));
        out.println("</p>");

        out.println("<b>Inactive Components</b>");
        out.println("<div>");

        // service interface name -> components implementing it
        Map<String, List<ComponentDescriptionDTO>> serviceImpls = new HashMap<String, List<ComponentDescriptionDTO>>();

        Iterator allComponentDescriptions = allComponents.iterator();
        while (allComponentDescriptions.hasNext()) {
            ComponentDescriptionDTO description = (ComponentDescriptionDTO) allComponentDescriptions.next();
            for (int i = 0; i < description.serviceInterfaces.length; i++) {

                addToMultiMap(serviceImpls, description.serviceInterfaces[i], description);
            }
        }

        // service interface name -> components blocked by it
        Map<String, List<ComponentDescriptionDTO>> missingServices = new HashMap<String, List<ComponentDescriptionDTO>>();
        Set<String> servicesAlreadyChecked = new HashSet<String>();

        allComponentDescriptions = allComponents.iterator();
        while (allComponentDescriptions.hasNext()) {
            ComponentDescriptionDTO description = (ComponentDescriptionDTO) allComponentDescriptions.next();

            collectMissingServices(description, scr, serviceImpls, missingServices, servicesAlreadyChecked);
        }

        // sort by most blocked components first
        List<Map.Entry<String, List<ComponentDescriptionDTO>>> list = new ArrayList<Map.Entry<String, List<ComponentDescriptionDTO>>>(missingServices.entrySet());
        Collections.sort(list, new Comparator<Map.Entry<String, List<ComponentDescriptionDTO>>>() {
            public int compare(Map.Entry<String, List<ComponentDescriptionDTO>> o1, Map.Entry<String, List<ComponentDescriptionDTO>> o2) {
                return o2.getValue().size() - o1.getValue().size();
            }
        });

        for (Map.Entry<String, List<ComponentDescriptionDTO>> entry : list) {
            List<ComponentDescriptionDTO> dependents = entry.getValue();
            out.println("<div class='toggle'>");
            out.println("<div class='ui-icon ui-icon-triangle-1-e' style='float: left'></div>");
            out.print("missing service: ");
            out.print(entry.getKey());
            out.print(" blocks ");
            out.print(dependents.size());
            out.println(" other components");
            out.println("<br>");
            out.println("<div class='toggle-content' style='display:none'>");
            for (ComponentDescriptionDTO dependent : dependents) {
                out.print("<p style='margin-left:30px'>");
                out.print(dependent.name);
                out.println("</p>");
            }
            out.println("</div>");
            out.println("</div>");
            out.println("<br>");
        }

        out.println("</div>");
    }

    private String getServiceStatusLine(Collection<ComponentDescriptionDTO> allComponents,
                                        ServiceReference<?>[] allServiceReferences) {
        final StringBuilder builder = new StringBuilder();
        builder.append("Component information: ");
        builder.append(allComponents.size());
        builder.append(" different components, ");
        long componentsWithActiveInstances = 0;
        long totalInstances = 0;
        long factories = 0;
        long totalFactoryInstances = 0;
        for (ComponentDescriptionDTO component : allComponents) {
            int count = scr.getComponentConfigurationDTOs(component).size();
            if (count > 0) {
                componentsWithActiveInstances += 1;
            }
            totalInstances += count;
            if (component.factory != null) {
                factories += 1;
                totalFactoryInstances += count;
            }
        }
        builder.append(componentsWithActiveInstances);
        builder.append(" active components, ");
        builder.append(totalInstances);
        builder.append(" active instances, ");
        builder.append(factories);
        builder.append(" factory components, ");
        // is always 0 ???
//        builder.append(totalFactoryInstances);
//        builder.append(" total factory instances, ");
        builder.append(allServiceReferences.length);
        builder.append(" service references");
        return builder.toString();
    }

    private void collectMissingServices(
        ComponentDescriptionDTO description,
        ServiceComponentRuntime scr,
        Map<String, List<ComponentDescriptionDTO>> serviceImpls,
        Map<String, List<ComponentDescriptionDTO>> missingServices,
        Set<String> servicesAlreadyChecked
    ) {
        Iterator components = scr.getComponentConfigurationDTOs(description).iterator();

        // first instance is enough
        if (components.hasNext()) {
            ComponentConfigurationDTO component = (ComponentConfigurationDTO) components.next();

            for (int i = 0; i < component.description.references.length; i++) {
                ReferenceDTO reference = component.description.references[i];
                SatisfiedReferenceDTO satisfiedRef = getSatisfiedReferenceDTO(component, reference.name);
                if (satisfiedRef == null) {
                    String serviceInterface = reference.interfaceName;
                    servicesAlreadyChecked.add(serviceInterface);

                    List impls = (List) serviceImpls.get(serviceInterface);
                    if (impls == null) {
                        addToMultiMap(missingServices, serviceInterface, description);
                    }
                }
            }
        }
    }

    private <K, V> void addToMultiMap(Map<K, List<V>> map, K key, V value) {
        List<V> components = map.get(key);
        if (components == null) {
            components = new ArrayList<V>();
            map.put(key, components);
        }
        components.add(value);
    }

    private SatisfiedReferenceDTO getSatisfiedReferenceDTO(final ComponentConfigurationDTO component, final String name) {
        for (int i = 0; i < component.satisfiedReferences.length; i++) {
            SatisfiedReferenceDTO ref = component.satisfiedReferences[i];
            if ( ref.name.equals(name)) {
                return ref;
            }
        }
        return null;
    }
}
