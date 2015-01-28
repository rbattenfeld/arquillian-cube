package org.arquillian.cube.impl.client.container;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Pattern;

import org.arquillian.cube.impl.docker.DockerClientExecutor;
import org.arquillian.cube.impl.util.BindingUtil;
import org.arquillian.cube.impl.util.ContainerUtil;
import org.arquillian.cube.spi.Binding;
import org.arquillian.cube.spi.Binding.PortBinding;
import org.arquillian.cube.spi.Cube;
import org.arquillian.cube.spi.CubeRegistry;
import org.jboss.arquillian.config.descriptor.api.ContainerDef;
import org.jboss.arquillian.container.spi.Container;
import org.jboss.arquillian.container.spi.ContainerRegistry;
import org.jboss.arquillian.container.spi.event.container.BeforeSetup;
import org.jboss.arquillian.core.api.Instance;
import org.jboss.arquillian.core.api.annotation.Inject;
import org.jboss.arquillian.core.api.annotation.Observes;

import com.github.dockerjava.api.model.ExposedPort;

public class RemapContainerController {

    private static final Pattern portPattern = Pattern.compile("(?i:.*port.*)");

    @Inject
    private Instance<DockerClientExecutor> dockerClientExecutor;
    
    public void remapContainer2(@Observes(precedence = 50) BeforeSetup event, CubeRegistry cubeRegistry, ContainerRegistry containerRegistry) throws InstantiationException, IllegalAccessException {
        
    	Container container = ContainerUtil.getContainerByDeployableContainer(containerRegistry, event.getDeployableContainer());
        if (container == null) {
            return;
        }

        Cube cube = cubeRegistry.getCube(container.getName());
        if (cube == null) {
            return; // No Cube found matching Container name, not managed by Cube
        }
        
        if (dockerClientExecutor.get() != null) {
	    	final Map<ExposedPort, com.github.dockerjava.api.model.Ports.Binding[]> publishedPorts = dockerClientExecutor.get().getpublishedPortBindings();
	        if (publishedPorts != null) {
		        for (Entry<ExposedPort, com.github.dockerjava.api.model.Ports.Binding[]> bindingEntry : publishedPorts.entrySet()) {
					final int exposedPort = bindingEntry.getKey().getPort();
					for (com.github.dockerjava.api.model.Ports.Binding b : bindingEntry.getValue()) {						
						final ContainerDef containerConfiguration = container.getContainerConfiguration();
						final List<String> portPropertiesFromArquillianConfigurationFile = filterArquillianConfigurationPropertiesByPortAttribute(containerConfiguration);
						final Class<?> configurationClass = container.getDeployableContainer().getConfigurationClass();
						final List<PropertyDescriptor> configurationClassPortFields = filterConfigurationClassPropertiesByPortAttribute(configurationClass);
						final Object newConfigurationInstance = configurationClass.newInstance();
			            for (PropertyDescriptor configurationClassPortField : configurationClassPortFields) {
			                if (portPropertiesFromArquillianConfigurationFile.contains(configurationClassPortField.getName())) {
			                	final int containerPort = getDefaultPortFromConfigurationInstance(newConfigurationInstance, configurationClass, configurationClassPortField);
			                    if (exposedPort == containerPort) {
			                        containerConfiguration.overrideProperty(configurationClassPortField.getName(), Integer.toString(b.getHostPort()));
			                    }	
			                }
			            }
					}
		        }
	        }
        }
    }

    public void remapContainer(@Observes(precedence = 20000) BeforeSetup event, CubeRegistry cubeRegistry,
            ContainerRegistry containerRegistry) throws InstantiationException, IllegalAccessException {

        Container container = ContainerUtil.getContainerByDeployableContainer(containerRegistry,
                event.getDeployableContainer());
        if (container == null) {
            return;
        }

        Cube cube = cubeRegistry.getCube(container.getName());
        if (cube == null) {
            return; // No Cube found matching Container name, not managed by Cube
        }

        Binding binding = BindingUtil.binding(cube.configuration());

        if (binding.arePortBindings()) {

            ContainerDef containerConfiguration = container.getContainerConfiguration();
            List<String> portPropertiesFromArquillianConfigurationFile = filterArquillianConfigurationPropertiesByPortAttribute(containerConfiguration);

            Class<?> configurationClass = container.getDeployableContainer().getConfigurationClass();
            List<PropertyDescriptor> configurationClassPortFields = filterConfigurationClassPropertiesByPortAttribute(configurationClass);

            Object newConfigurationInstance = configurationClass.newInstance();

            for (PropertyDescriptor configurationClassPortField : configurationClassPortFields) {
                if (!portPropertiesFromArquillianConfigurationFile.contains(configurationClassPortField.getName())) {
                    // This means that port has not configured in arquillian.xml and it will use default value.
                    // In this case is when remapping should be activated to adequate the situation according to
                    // Arquillian
                    // Cube exposed ports.

                    int containerPort = getDefaultPortFromConfigurationInstance(newConfigurationInstance,
                            configurationClass, configurationClassPortField);

                    PortBinding bindingForExposedPort = null;
                    if ((bindingForExposedPort = binding.getBindingForExposedPort(containerPort)) != null) {
                        containerConfiguration.overrideProperty(configurationClassPortField.getName(),
                                Integer.toString(bindingForExposedPort.getBindingPort()));
                    }

                }
            }
        }
    }

    private int getDefaultPortFromConfigurationInstance(Object configurationInstance, Class<?> configurationClass,
            PropertyDescriptor fieldName) {

        try {
            Method method = fieldName.getReadMethod();
            return (int) method.invoke(configurationInstance);
        } catch (SecurityException e) {
            throw new IllegalArgumentException(e);
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException(e);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(e);
        } catch (InvocationTargetException e) {
            throw new IllegalArgumentException(e);
        }

    }

    private List<String> filterArquillianConfigurationPropertiesByPortAttribute(ContainerDef containerDef) {
        List<String> fields = new ArrayList<String>();

        for (Entry<String, String> entry : containerDef.getContainerProperties().entrySet()) {
            if (portPattern.matcher(entry.getKey()).matches()) {
                fields.add(entry.getKey());
            }
        }

        return fields;
    }

    private List<PropertyDescriptor> filterConfigurationClassPropertiesByPortAttribute(Class<?> configurationClass) {

        List<PropertyDescriptor> fields = new ArrayList<PropertyDescriptor>();

        try {
            PropertyDescriptor[] propertyDescriptors = Introspector.getBeanInfo(configurationClass, Object.class)
                    .getPropertyDescriptors();

            for (PropertyDescriptor propertyDescriptor : propertyDescriptors) {
                String propertyName = propertyDescriptor.getName();

                if (portPattern.matcher(propertyName).matches()) {
                    fields.add(propertyDescriptor);
                }
            }

        } catch (IntrospectionException e) {
            throw new IllegalArgumentException(e);
        }

        return fields;
    }

}
