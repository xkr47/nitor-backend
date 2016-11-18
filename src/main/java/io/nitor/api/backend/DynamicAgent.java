package io.nitor.api.backend;

import static java.lang.System.err;
import static java.lang.System.getProperty;
import static java.lang.management.ManagementFactory.getRuntimeMXBean;
import static java.nio.file.Files.createTempFile;
import static java.nio.file.Files.newOutputStream;
import static java.util.jar.Attributes.Name.MANIFEST_VERSION;

import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import org.mortbay.jetty.alpn.agent.Premain;

public class DynamicAgent {
    public static void agentmain(String args, Instrumentation inst) throws Exception {
        Premain.premain(args, inst);
    }

    static boolean enableJettyAlpn() {
        if (getProperty("java.version", "").startsWith("9")) {
            err.println("Netty does not jet support alpn on java 9");
            return false; // java9 supports alpn natively but netty does not yet support java9
        }

        Path javaHome = Paths.get(getProperty("java.home"));
        Optional<Path> toolsPath =
            Stream.of(Paths.get("lib", "tools.jar"),
                Paths.get("..", "lib", "tools.jar"))
                .map(javaHome::resolve)
                .filter(Files::exists)
                .findFirst();
        if (!toolsPath.isPresent()) {
            err.println("Could not find tools.jar from java installation at " + javaHome);
            return false;
        }

        try {
            Path agentPath = createTempFile("dynamic-agent", ".jar");
            agentPath.toFile().deleteOnExit();
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().putValue("Agent-Class", DynamicAgent.class.getName());
            JarOutputStream out = new JarOutputStream(newOutputStream(agentPath), manifest);
            out.close();

            URL[] urls = new URL[] {
                toolsPath.get().toUri().toURL()
            };

            URLClassLoader loader = new URLClassLoader(urls, null);
            Class<?> virtualMachineClass = loader.loadClass("com.sun.tools.attach.VirtualMachine");

            String nameOfRunningVM = getRuntimeMXBean().getName();
            int p = nameOfRunningVM.indexOf('@');
            if (p < 0) {
                err.println("Could not parse current jvm pid");
                return false;
            }
            String pid = nameOfRunningVM.substring(0, p);

            Object virtualMachine = virtualMachineClass.getMethod("attach", String.class).invoke(null, pid);
            try {
                virtualMachineClass.getMethod("loadAgent", String.class).invoke(virtualMachine, agentPath.toString());
            } finally {
                virtualMachineClass.getMethod("detach").invoke(virtualMachine);
            }

            return true;
        } catch (Exception e) {
            err.println("Could not initialize jetty-alpn-agent correctly: " + e);
            return false;
        }
    }
}
