package org.arquillian.cube.impl.client.container;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Map.Entry;

import org.arquillian.cube.impl.client.CubeConfiguration;
import org.arquillian.cube.impl.docker.DockerClientExecutor;
import org.arquillian.cube.impl.util.ContainerUtil;
import org.arquillian.cube.spi.Binding;
import org.arquillian.cube.spi.Binding.PortBinding;
import org.arquillian.cube.spi.Cube;
import org.arquillian.cube.spi.CubeRegistry;
import org.jboss.arquillian.container.spi.Container;
import org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.arquillian.container.spi.client.protocol.metadata.Servlet;
import org.jboss.arquillian.container.spi.context.annotation.DeploymentScoped;
import org.jboss.arquillian.core.api.Instance;
import org.jboss.arquillian.core.api.InstanceProducer;
import org.jboss.arquillian.core.api.annotation.Inject;
import org.jboss.arquillian.core.api.annotation.Observes;
import org.jboss.arquillian.core.spi.EventContext;

import com.github.dockerjava.api.model.ExposedPort;

public class ProtocolMetadataUpdater {

    @Inject @DeploymentScoped
    private InstanceProducer<ProtocolMetaData> protocolMetaDataProducer;

    @Inject @DeploymentScoped
    private InstanceProducer<CubeConfiguration> configurationProducer;
    
    @Inject
    private Instance<DockerClientExecutor> dockerClientExecutor;
    
    public void update(@Observes EventContext<ProtocolMetaData> eventContext, Container container, CubeRegistry registry) {
        
        ProtocolMetaData originalMetaData = eventContext.getEvent();
        ProtocolMetaData updatedMetaData = new ProtocolMetaData();
        boolean updated = false;
        Cube cube = registry.getCube(container.getName());
        if(cube != null) {
            Binding binding = cube.bindings();
            String gatewayIp = cube.bindings().getIP();            
            for(Object contextObj : originalMetaData.getContexts()) {
                if(contextObj instanceof HTTPContext) {
                    HTTPContext context = (HTTPContext)contextObj;
                    PortBinding mapped = binding.getBindingForExposedPort(context.getPort());
                    String ip = context.getHost();
                    int port = context.getPort();
//                    int newPort = remapExposedPort(port);
//                    if (port != newPort) {
//                    	updated = true;
//                        port = newPort;
//                    } 
                    if(mapped != null && port != mapped.getBindingPort()) {
                        updated = true;
                        port = mapped.getBindingPort();
                    } 
                    Boolean isContainerVisible = (Boolean) cube.configuration().get("containerVisible");
                    if (isContainerVisible != null && !isContainerVisible) {
                    	int newPort = remapExposedPort(port);
                        if (port != newPort) {
                        	updated = true;
                            port = newPort;
                        } 
                    	String dockerHost = getDockerHost(configurationProducer.get().getDockerServerUri());
                    	if (!dockerHost.equals(ip)) {                   
	                    	updated = true;
	                        ip = dockerHost;
	                    }
                    } else if (!gatewayIp.equals(ip)) {
                        updated = true;
                        ip = gatewayIp;
                    }
                    if(updated) {
                        HTTPContext newContext = new HTTPContext(ip, port);
                        for(Servlet servlet : context.getServlets()) {
                            newContext.add(servlet);
                        }
                        updatedMetaData.addContext(newContext);
                    }

                } else {
                    updatedMetaData.addContext(contextObj);
                }
            }
        }

        if(updated) {
            protocolMetaDataProducer.set(updatedMetaData);
        } else {
            eventContext.proceed();
        }
    }
    
    private int remapExposedPort(final int port) {
    	if (dockerClientExecutor.get() != null) {
	    	final Map<ExposedPort, com.github.dockerjava.api.model.Ports.Binding[]> publishedPorts = dockerClientExecutor.get().getpublishedPortBindings();
	        if (publishedPorts != null) {
		        for (Entry<ExposedPort, com.github.dockerjava.api.model.Ports.Binding[]> bindingEntry : publishedPorts.entrySet()) {
					final int exposedPort = bindingEntry.getKey().getPort();
					for (com.github.dockerjava.api.model.Ports.Binding b : bindingEntry.getValue()) {
						if (exposedPort == port) {
							return b.getHostPort();
						}
					}
		        }
	        }
    	}
        return port;
    }
    
    private String getDockerHost(final String serverUri) {
    	try {
    		final URI serverUrid = new URI(serverUri);
			return serverUrid.getHost();
		} catch (final URISyntaxException e) {
			throw new RuntimeException(e);
		}
    }
}
