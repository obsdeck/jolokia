package org.jolokia.backend;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.*;

import javax.management.*;

import org.jolokia.detector.*;
import org.jolokia.handler.JsonRequestHandler;
import org.jolokia.jmx.JolokiaMBeanServerUtil;
import org.jolokia.request.JmxRequest;
import org.jolokia.util.*;

/*
 *  Copyright 2009-2010 Roland Huss
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */


/**
 * Handler for finding and merging various MBeanServers locally when used
 * as an agent.
 *
 * @author roland
 * @since Jun 15, 2009
 */
public class MBeanServerHandler implements MBeanServerHandlerMBean, MBeanRegistration {

    // The MBeanServers to use
    private Set<MBeanServer>           mBeanServers;
    private Set<MBeanServerConnection> mBeanServerConnections;

    // Optional domain for registering this handler as a MBean
    private String qualifier;

    // Information about the server environment
    private ServerHandle serverHandle;

    // Handles remembered for unregistering
    private final List<MBeanHandle> mBeanHandles = new ArrayList<MBeanHandle>();

    /**
     * Create a new MBeanServer handler who is responsible for managing multiple intra VM {@link MBeanServer} at once
     * An optional qualifier used for registering this object as an MBean is taken from the given configuration as well
     *
     * @param pConfig configuration for this agent which is also given to the {@see ServerHandle#postDetect()} method for
     *                special initialization.
     *
     * @param pLogHandler log handler used for logging purposes
     */
    public MBeanServerHandler(Map<ConfigKey, String> pConfig, LogHandler pLogHandler) {
        // A qualifier, if given, is used to add the MBean Name of this MBean
        qualifier = pConfig.get(ConfigKey.MBEAN_QUALIFIER);
        List<ServerDetector> detectors = lookupDetectors();
        initMBeanServers(detectors);
        initServerHandle(pConfig, pLogHandler, detectors);
        initMBean();
    }

    /**
     * Initialize the server handle.
     * @param pConfig configuration passed through to the server detectors
     * @param pLogHandler used for putting out diagnostic messags
     * @param pDetectors all detectors known
     */
    private void initServerHandle(Map<ConfigKey, String> pConfig, LogHandler pLogHandler, List<ServerDetector> pDetectors) {
        serverHandle = detectServers(pDetectors, pLogHandler);
        serverHandle.postDetect(mBeanServers, pConfig, pLogHandler);
    }

    /**
     * Dispatch a request to the MBeanServer which can handle it
     *
     * @param pRequestHandler request handler to be called with an MBeanServer
     * @param pJmxReq the request to dispatch
     * @return the result of the request
     */
    public Object dispatchRequest(JsonRequestHandler pRequestHandler, JmxRequest pJmxReq)
            throws InstanceNotFoundException, AttributeNotFoundException, ReflectionException, MBeanException {
        serverHandle.preDispatch(mBeanServers,pJmxReq);
        if (pRequestHandler.handleAllServersAtOnce(pJmxReq)) {
            try {
                return pRequestHandler.handleRequest(mBeanServerConnections,pJmxReq);
            } catch (IOException e) {
                throw new IllegalStateException("Internal: IOException " + e + ". Shouldn't happen.",e);
            }
        } else {
            return handleRequest(pRequestHandler, pJmxReq);
        }
    }

    /**
     * Register a MBean under a certain name to the first platform MBeanServer
     *
     * @param pMBean MBean to register
     * @param pOptionalName optional name under which the bean should be registered. If not provided,
     * it depends on whether the MBean to register implements {@link javax.management.MBeanRegistration} or
     * not.
     *
     * @return the name under which the MBean is registered.
     */
    public final ObjectName registerMBean(Object pMBean,String ... pOptionalName)
            throws MalformedObjectNameException, NotCompliantMBeanException, InstanceAlreadyExistsException {
        synchronized (mBeanHandles) {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            try {
                String name = pOptionalName != null && pOptionalName.length > 0 ? pOptionalName[0] : null;
                ObjectName registeredName = serverHandle.registerMBeanAtServer(server, pMBean, name);
                mBeanHandles.add(new MBeanHandle(server,registeredName));
                return registeredName;
            } catch (RuntimeException exp) {
                throw new IllegalStateException("Could not register " + pMBean + ": " + exp, exp);
                } catch (MBeanRegistrationException exp) {
                throw new IllegalStateException("Could not register " + pMBean + ": " + exp, exp);
            }
        }
    }

    /**
     * Unregister all previously registered MBean. This is tried for all previously
     * registered MBeans
     *
     * @throws JMException if an exception occurs during unregistration
     */
    public final void unregisterMBeans() throws JMException {
        synchronized (mBeanHandles) {
            List<JMException> exceptions = new ArrayList<JMException>();
            List<MBeanHandle> unregistered = new ArrayList<MBeanHandle>();
            for (MBeanHandle handle : mBeanHandles) {
                try {
                    unregistered.add(handle);
                    handle.server.unregisterMBean(handle.objectName);
                } catch (InstanceNotFoundException e) {
                    exceptions.add(e);
                } catch (MBeanRegistrationException e) {
                    exceptions.add(e);
                }
            }
            // Remove all successfully unregistered handles
            mBeanHandles.removeAll(unregistered);

            // Throw error if any exception occured during unregistration
            if (exceptions.size() == 1) {
                throw exceptions.get(0);
            } else if (exceptions.size() > 1) {
                StringBuilder ret = new StringBuilder();
                for (JMException e : exceptions) {
                    ret.append(e.getMessage()).append(", ");
                }                
                throw new JMException(ret.substring(0, ret.length() - 2));
            }
        }
    }

    /**
     * Get the set of MBeanServers found
     *
     * @return set of mbean servers
     */
    public Set<MBeanServer> getMBeanServers() {
        return Collections.unmodifiableSet(mBeanServers);
    }

    /**
     * Get information about the detected server this agent is running on.
     *
     * @return the server info if detected or <code>null</code> if no server
     *          could be detected.
     */
    public ServerHandle getServerHandle() {
        return serverHandle;
    }

    // =================================================================================

    // Handle a given request
    private Object handleRequest(JsonRequestHandler pRequestHandler, JmxRequest pJmxReq)
            throws ReflectionException, MBeanException, AttributeNotFoundException, InstanceNotFoundException {
        AttributeNotFoundException attrException = null;
        InstanceNotFoundException objNotFoundException = null;
        for (MBeanServer s : mBeanServers) {
            try {
                return pRequestHandler.handleRequest(s, pJmxReq);
            } catch (InstanceNotFoundException exp) {
                // Remember exceptions for later use
                objNotFoundException = exp;
            } catch (AttributeNotFoundException exp) {
                attrException = exp;
            } catch (IOException exp) {
                throw new IllegalStateException("I/O Error while dispatching",exp);
            }
        }
        if (attrException != null) {
            throw attrException;
        }
        // Must be there, otherwise we would not have left the loop
        throw objNotFoundException;
    }

    /**
     * Initialise this server handler and register as an MBean
     */
    private void initMBean()  {
        try {
            registerMBean(this,getObjectName());
        } catch (InstanceAlreadyExistsException exp) {
            // This is no problem, since this MBeanServerHandlerMBean holds only global information
            // with no special state (so all instances of this MBean behave similar)
            // This exception can happen, when multiple servlets get registered within the same JVM
        } catch (MalformedObjectNameException e) {
            // Cannot happen, otherwise this is a bug. We should be always provide our own name in correct
            // form.
            throw new IllegalStateException("Internal Error: Own ObjectName " + getObjectName() + " is malformed",e);
        } catch (NotCompliantMBeanException e) {
            // Same here
            throw new IllegalStateException("Internal Error: " + this.getClass().getName() + " is not a compliant MBean",e);
        }
    }

    // Lookup all registered detectors + a default detector
    private List<ServerDetector> lookupDetectors() {
        List<ServerDetector> detectors =
                ServiceObjectFactory.createServiceObjects("META-INF/detectors-default", "META-INF/detectors");
        // An detector at the end of the chain in order to get a default handle
        detectors.add(new FallbackServerDetector());
        return detectors;
    }

    /**
     * Use various ways for getting to the MBeanServer which should be exposed via this
     * servlet.
     *
     * <ul>
     *   <li>If running in JBoss, use <code>org.jboss.mx.util.MBeanServerLocator</code>
     *   <li>Use {@link javax.management.MBeanServerFactory#findMBeanServer(String)} for
     *       registered MBeanServer and take the <b>first</b> one in the returned list
     *   <li>Finally, use the {@link java.lang.management.ManagementFactory#getPlatformMBeanServer()}
     * </ul>
     *
     * @throws IllegalStateException if no MBeanServer could be found.
     * @param pDetectors detectors which might have extra possibilities to add MBeanServers
     */
    private void initMBeanServers(List<ServerDetector> pDetectors) {

        // Check for JBoss MBeanServer via its utility class
        mBeanServers = new LinkedHashSet<MBeanServer>();

        // Create and add our own JolokiaMBeanServer first
        MBeanServer jolokiaMBeanServer = JolokiaMBeanServerUtil.getJolokiaMBeanServer();
        mBeanServers.add(jolokiaMBeanServer);

        // Let every detector add its own MBeanServer
        for (ServerDetector detector : pDetectors) {
            detector.addMBeanServers(mBeanServers);
        }

        // All MBean Server known by the MBeanServerFactory
        List<MBeanServer> beanServers = MBeanServerFactory.findMBeanServer(null);
        if (beanServers != null) {
            mBeanServers.addAll(beanServers);
        }

        // Last entry is always the platform MBeanServer
        mBeanServers.add(ManagementFactory.getPlatformMBeanServer());

        // Copy over servers into connection set. Required for proper generic usage
        mBeanServerConnections = new LinkedHashSet<MBeanServerConnection>();
        for (MBeanServer server : mBeanServers) {
            mBeanServerConnections.add(server);
        }
	}

    // Detect the server by delegating it to a set of predefined detectors. These will be created
    // by a lookup mechanism, queried and thrown away after this method
    private ServerHandle detectServers(List<ServerDetector> pDetectors, LogHandler pLogHandler) {
        // Now detect the server
        for (ServerDetector detector : pDetectors) {
            try {
                ServerHandle info = detector.detect(mBeanServers);
                if (info != null) {
                    return info;
                }
            } catch (Exception exp) {
                // We are defensive here and wont stop the servlet because
                // there is a problem with the server detection. A error will be logged
                // nevertheless, though.
                pLogHandler.error("Error while using detector " + detector.getClass().getSimpleName() + ": " + exp,exp);
            }
        }
        return null;
    }
    
    // =====================================================================================

    // MBean exported debugging method

    /**
     * Get a description of all MBeanServers found
     * @return a description of all MBeanServers along with their stored MBeans
     */
    public String mBeanServersInfo() {
        StringBuffer ret = new StringBuffer();
        Set<MBeanServer> servers = getMBeanServers();

        ret.append("Found ").append(servers.size()).append(" MBeanServers\n");
        for (MBeanServer s : servers) {
            ret.append("    ")
                    .append("++ ")
                    .append(s.toString())
                    .append(": default domain = ")
                    .append(s.getDefaultDomain())
                    .append(", ")
                    .append(s.getMBeanCount())
                        .append(" MBeans\n");

            ret.append("        Domains:\n");
            boolean javaLangFound = false;
            for (String d : s.getDomains()) {
                if ("java.lang".equals(d)) {
                    javaLangFound = true;
                }
                appendDomainInfo(ret, s, d);
            }
            if (!javaLangFound) {
                // JBoss fails to list java.lang in its domain list
                appendDomainInfo(ret,s,"java.lang");
            }
        }
        ret.append("\n");
        ret.append("Platform MBeanServer: ")
                .append(ManagementFactory.getPlatformMBeanServer())
                .append("\n");
        return ret.toString();
    }

    private void appendDomainInfo(StringBuffer pRet, MBeanServer pServer, String pDomain) {
        try {
            pRet.append("         == ").append(pDomain).append("\n");
            Set<ObjectInstance> beans = pServer.queryMBeans(new ObjectName(pDomain + ":*"),null);
            for (ObjectInstance o : beans) {
                String n = o.getObjectName().getCanonicalKeyPropertyListString();
                pRet.append("              ").append(n).append("\n");
            }
        } catch (MalformedObjectNameException e) {
            // Shouldnt happen
            pRet.append("              INTERNAL ERROR: ").append(e).append("\n");
        }
    }

    // ==============================================================================================
    // Needed for providing the name for our MBean
    /** {@inheritDoc} */
    public ObjectName preRegister(MBeanServer server, ObjectName name) throws MalformedObjectNameException {
        return new ObjectName(getObjectName());
    }

    /** {@inheritDoc} */
    public final String getObjectName() {
        return OBJECT_NAME + (qualifier != null ? "," + qualifier : "");
    }

    /** {@inheritDoc} */
    public void postRegister(Boolean registrationDone) {
    }

    /** {@inheritDoc} */
    public void preDeregister() {
    }

    /** {@inheritDoc} */
    public void postDeregister() {
    }

    // ==================================================================================
    // Handle for remembering registered MBeans
    private static final class MBeanHandle {
        private ObjectName objectName;
        private MBeanServer server;

        private MBeanHandle(MBeanServer pServer, ObjectName pRegisteredName) {
            server = pServer;
            objectName = pRegisteredName;
        }
    }

    // ==================================================================================
    // Fallback server detector which matches always

    private static class NullServerHandle extends ServerHandle {
        /**
         * Empty constructor initializing the server handle completely with null values.
         */
        public NullServerHandle() {
            super(null,null,null,null,null);
        }
    }

    private static class FallbackServerDetector extends AbstractServerDetector {
        /** {@inheritDoc} */
        public ServerHandle detect(Set<MBeanServer> pMbeanServers) {
            return new NullServerHandle();
        }
    }
}
