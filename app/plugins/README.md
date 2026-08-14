# JVM plugin directory

Place self-contained, manifested Yano plugin bundle JARs in this directory,
then restart the node. The packaged JVM runtime snapshots and validates every
regular `*.jar` here before activating the selected catalog.

Native distributions do not support dynamic JAR loading; include native
plugins at application build time instead.
