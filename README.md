# osgi-troubleshoot
Apache Felix Webconsole Plugin for troubleshooting OSGi bundles and services

More info in [FELIX-5410](https://issues.apache.org/jira/browse/FELIX-5410).

## Build and installation

    mvn
    mvn sling:install -Dsling.url=http://localhost:4502/system/console
    
Adapt host and port in `sling.url` accordingly to your server.

Then go to Felix Webconsole > OSGi > Troubleshoot:

<http://localhost:4502/system/console/troubleshoot>

