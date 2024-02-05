{inceptionYear=${project.inceptionYear}}
{currentYear=${currentYear}}
# AuthzForce Server - Manual installation

This guide provides the procedure to install the AuthzForce server from the tarball distribution, including system requirements and troubleshooting instructions. 

## System Requirements
* CPU frequency: 2.6 GHz min
* CPU architecture: i686/x86_64
* RAM: 4GB min
* Disk space: 10 GB min
* File system: ext4
* Operating System: Ubuntu 23.04 
* Java environment: 
    * JRE 17 either from OpenJDK or Oracle; 
    * Tomcat 10.x.

## Installation
### Minimal
1. If you don't have a JRE 17 already installed, you may do it on the command-line as follows, depending on your JRE preference:
    * If you prefer OpenJDK: `$ sudo apt install openjdk-17-jdk`
    * If you prefer Oracle JRE, follow the instructions from [WEB UPD8](http://www.webupd8.org/2012/09/install-oracle-java-8-in-ubuntu-via-ppa.html). In the end, you should have the package `oracle-java17-installer` installed.
1. If you don't have Tomcat 10 already installed, you may do it on the command-line: `$ sudo apt install tomcat10`
1. Download AuthzForce server tarball distribution from the [Maven Central Repository](http://repo1.maven.org/maven2/org/ow2/authzforce/authzforce-ce-server-dist/${project.version}/authzforce-ce-server-dist-${project.version}.tar.gz). You get a file called ``authzforce-ce-server-dist-${project.version}.tar.gz``.
1. Copy this file to the host where you want to install AuthzForce Server.
1. For security purposes, Tomcat should be run as an unprivileged user (i.e. not `root`). If you installed Tomcat as shown above, this user is `tomcat`. Let us assume that `tomcat` is the user (and group) that will run the Tomcat service in your case, and `/opt` is the directory where you want to install AuthzForce server. Please replace both names according to your setup. `$CATALINA_BASE` is a Tomcat environment-specific property, usually equal to `$CATALINA_HOME`, i.e. the root directory of your Tomcat installation ([more information](https://tomcat.apache.org/tomcat-9.0-doc/introduction.html)). If you installed Tomcat as shown above, `$CATALINA_BASE = /var/lib/tomcat10`. From the directory where you copied the tarball for installation, run the following commands:  

    ```shell
    $ sudo tar xvzf authzforce-ce-server-dist-${project.version}.tar.gz --directory /opt
    $ cd /opt
    $ sudo ln -s authzforce-ce-server-${project.version} authzforce-ce-server
    $ sudo chown -RH tomcat authzforce-ce-server
    $ sudo chgrp -RH tomcat authzforce-ce-server
    $ sudo cp /opt/authzforce-ce-server/conf/context.xml.sample $CATALINA_BASE/conf/Catalina/localhost/authzforce-ce.xml
    ```
1. If you did not use `/opt` as installation directory, replace **ALL** occurrences of `/opt` in the webapp context configuration file `authzforce-ce.xml` according to your setup.
1. You may restart Tomcat server now. For instance, if you installed Tomcat as shown above, do it as follows:

    ```shell
    $ systemctl restart tomcat10
    ```

    **Known issue: lack of entropy may cause delays in Tomcat 7+ start up on virtual machines in particular: [more info on Entropy Source issue](https://wiki.apache.org/tomcat/HowTo/FasterStartUp#Entropy_Source). So beware.**

1. When the webapp is up and running, you should get a HTTP response with status code 200 to this HTTP request with curl tool (replace 8080 with the port that Tomcat is listening to):
    
    ```shell
    $ curl --verbose --show-error --write-out '\n' --request GET http://localhost:8080/authzforce-ce/domains
    ```
    
Now you can start playing with the REST API as defined by the WADL document that you can retrieve with a wget command (will save the wadl to local file `authzforce.wadl`):

```shell
$ wget -v -O authzforce.wadl http://localhost:8080/authzforce-ce/?_wadl
```

### Advanced 
Tomcat default setup is not suitable for production! If you are targeting a production environment, you have to carry out extra installation and configuration steps to address non-functional aspects: security (including availability), performance, etc. For performance aspects, we strongly recommend reading and applying - when relevant - the guidelines from the following links:

- [Performance tuning best practices for VMware Apache Tomcat](http://kb.vmware.com/kb/2013486);
- [How to optimize Tomcat performance in production](http://www.genericarticles.com/mediawiki/index.php?title=How_to_optimize_tomcat_performance_in_production);
- [Apache Tomcat Tuning Guide for REST/HTTP APIs](https://javamaster.wordpress.com/2013/03/13/apache-tomcat-tuning-guide/).

Last but not least, please check the *More information* section below.

## Troubleshooting
If Tomcat fails to (re)start, check for any Tomcat high-level error in Tomcat log directory: `$CATALINA_BASE/logs`.
One common reason for failure is Tomcat default configuration may specify a value for the Java `Xmx` flag that is too low for the AuthzForce webapp. Make sure Tomcat is configured with `Xmx` at 1GB or more, 2 GB recommended. For example:

```shell
$ export JAVA_OPTS='"-Djava.awt.headless=true -Djavax.xml.accessExternalSchema=all -Xms1024m -Xmx1024m -server"'
$ sed -i 's|^\(JAVA_OPTS\s*=\s*\).*$|\1'"$JAVA_OPTS"'|' /etc/default/tomcat10
$ systemctl restart tomcat10
```

If Tomcat is started but AuthzForce webapp deployment fails, check for any webapp-specific error in log file: `$CATALINA_BASE/logs/authzforce-ce/error.log`

## More information
For more information, go to the [online documentation](http://authzforce-ce-fiware.readthedocs.io/en/) and select the version matching your software release at the bottom of the page.
