package mill.main.client;

import mill.main.client.lock.Locked;
import mill.main.client.lock.Locks;
import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.lang.Math;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * This is a Java implementation to speed up repetitive starts.
 * A Scala implementation would result in the JVM loading much more classes almost doubling the start-up times.
 */
public class MillClientMain {

    // use methods instead of constants to avoid inlining by compiler
    public static final int ExitClientCodeCannotReadFromExitCodeFile() {
        return 1;
    }

    public static final int ExitServerCodeWhenIdle() {
        return 0;
    }

    public static final int ExitServerCodeWhenVersionMismatch() {
        return 101;
    }

    static void initServer(String lockBase, boolean setJnaNoSys) throws IOException, URISyntaxException {
        List<String> l = new ArrayList<>();
        l.addAll(MillEnv.millLaunchJvmCommand(setJnaNoSys));
        l.add("mill.runner.MillServerMain");
        l.add(lockBase);

        File stdout = new java.io.File(lockBase + "/stdout");
        File stderr = new java.io.File(lockBase + "/stderr");

        new ProcessBuilder()
            .command(l)
            .redirectOutput(stdout)
            .redirectError(stderr)
            .start();
    }

    public static void main(String[] args) throws Exception {
        boolean runIsolated = false;
        if (args.length > 0) {
            String firstArg = args[0];
            runIsolated =
                Arrays.asList("--interactive", "--no-server", "--repl", "--bsp", "--help")
                    .contains(firstArg) || firstArg.startsWith("-i");
        }
        if (!runIsolated) {
            // WSL2 has the directory /run/WSL/ and WSL1 not.
            String osVersion =System.getProperty("os.version");
            if(osVersion != null && (osVersion.contains("icrosoft") || osVersion.contains("WSL"))) {
                // Server-Mode not supported under WSL1
                runIsolated = true;
            }
        }

        if (runIsolated) {
            // start in no-server mode
            IsolatedMillMainLoader.runMain(args);
        } else try {
            // start in client-server mode
            int exitCode = main0(args);
            if (exitCode == ExitServerCodeWhenVersionMismatch()) {
                exitCode = main0(args);
            }
            System.exit(exitCode);
        } catch (MillServerCouldNotBeStarted e) {
            // TODO: try to run in-process
            System.err.println("Could not start a Mill server process.\n" +
                "This could be caused by too many already running Mill instances " +
                "or by an unsupported platform.\n");
            if (IsolatedMillMainLoader.load().canLoad) {
                System.err.println("Trying to run Mill in-process ...");
                IsolatedMillMainLoader.runMain(args);
            } else {
                System.err.println("Loading Mill in-process isn't possible.\n" +
                    "Please check your Mill installation!");
                throw e;
            }
        }
    }

    public static int main0(String[] args) throws Exception {

        final boolean setJnaNoSys = System.getProperty("jna.nosys") == null;
        if (setJnaNoSys) {
            System.setProperty("jna.nosys", "true");
        }

        final String versionAndJvmHomeEncoding = Util.sha1Hash(BuildInfo.millVersion + System.getProperty("java.home"));
        final int serverProcessesLimit = getServerProcessesLimit(versionAndJvmHomeEncoding);

        int index = 0;
        while (index < serverProcessesLimit) {
            index++;
            final String lockBase = "out/" + OutFiles.millWorker() + versionAndJvmHomeEncoding + "-" + index;
            java.io.File lockBaseFile = new java.io.File(lockBase);
            final File stdout = new java.io.File(lockBaseFile, "stdout");
            final File stderr = new java.io.File(lockBaseFile, "stderr");

            int attempts = 0;
            while (attempts < 3) {
                try {
                    lockBaseFile.mkdirs();

                    final int refeshIntervalMillis = 2;

                    try (
                        Locks locks = Locks.files(lockBase);
                        final FileToStreamTailer stdoutTailer = new FileToStreamTailer(stdout, System.out, refeshIntervalMillis);
                        final FileToStreamTailer stderrTailer = new FileToStreamTailer(stderr, System.err, refeshIntervalMillis);
                    ) {
                        Locked clientLock = locks.clientLock.tryLock();
                        if (clientLock != null) {
                            stdoutTailer.start();
                            stderrTailer.start();
                            final int exitCode = run(
                                lockBase,
                                () -> {
                                    try {
                                        initServer(lockBase, setJnaNoSys);
                                    } catch (Exception e) {
                                        throw new RuntimeException(e);
                                    }
                                },
                                locks,
                                System.in,
                                System.out,
                                System.err,
                                args,
                                System.getenv());

                            // Here, we ensure we process the tails of the output files before interrupting
                            // the threads
                            stdoutTailer.flush();
                            stderrTailer.flush();
                            clientLock.release();
                            return exitCode;
                        }
                    }
                } catch (Exception e) {
                    for (File file : lockBaseFile.listFiles()) {
                        file.delete();
                    }
                } finally {
                    attempts++;
                }
            }
        }
        throw new MillServerCouldNotBeStarted("Reached max server processes limit: " + serverProcessesLimit);
    }

    public static class MillServerCouldNotBeStarted extends Exception {
        public MillServerCouldNotBeStarted(String msg) {
            super(msg);
        }
    }

    public static int run(
        String lockBase,
        Runnable initServer,
        Locks locks,
        InputStream stdin,
        OutputStream stdout,
        OutputStream stderr,
        String[] args,
        Map<String, String> env) throws Exception {

        try (FileOutputStream f = new FileOutputStream(ServerFiles.runArgs(lockBase))) {
            f.write(System.console() != null ? 1 : 0);
            Util.writeString(f, BuildInfo.millVersion);
            Util.writeArgs(args, f);
            Util.writeMap(env, f);
        }

        boolean serverInit = false;
        if (locks.processLock.probe()) {
            serverInit = true;
            initServer.run();
        }
        while (locks.processLock.probe()) Thread.sleep(3);

        String socketName = ServerFiles.pipe(lockBase);
        AFUNIXSocketAddress addr = AFUNIXSocketAddress.of(new File(socketName));

        long retryStart = System.currentTimeMillis();
        Socket ioSocket = null;
        Throwable socketThrowable = null;
        while (ioSocket == null && System.currentTimeMillis() - retryStart < 1000) {
            try {
                ioSocket = AFUNIXSocket.connectTo(addr);
            } catch (Throwable e) {
                socketThrowable = e;
                Thread.sleep(1);
            }
        }

        if (ioSocket == null) {
            throw new Exception("Failed to connect to server", socketThrowable);
        }

        InputStream outErr = ioSocket.getInputStream();
        OutputStream in = ioSocket.getOutputStream();
        ProxyStreamPumper outPump = new ProxyStreamPumper(outErr, stdout, stderr);
        InputPumper inPump = new InputPumper(() -> stdin, () -> in, true);
        Thread outThread = new Thread(outPump, "outPump");
        outThread.setDaemon(true);
        Thread inThread = new Thread(inPump, "inPump");
        inThread.setDaemon(true);
        outThread.start();
        inThread.start();

        locks.serverLock.await();

        // Although the process that the server was running has terminated and the server has sent all the stdout/stderr
        // over the unix pipe and released its lock we don't know that all the data has arrived at the client
        // The outThread of the ProxyStreamPumper will not close until the socket is closed (so we can't join on it)
        // but we also can't close the socket until all the data has arrived. Catch 22. We could signal termination
        // in the stream (ProxyOutputStream / ProxyStreamPumper) but that would require a new protocol.
        // So we just wait until there has been X ms with no data

        outPump.getLastData().waitForSilence(50);

        try {
            return Integer.parseInt(Files.readAllLines(Paths.get(ServerFiles.exitCode(lockBase))).get(0));
        } catch (Throwable e) {
            return ExitClientCodeCannotReadFromExitCodeFile();
        } finally {
            ioSocket.close();
        }
    }

    // 5 processes max
    private static int getServerProcessesLimit(String jvmHomeEncoding) {
        File outFolder = new File("out");
        String[] totalProcesses = outFolder.list((dir, name) -> name.startsWith(OutFiles.millWorker()));
        String[] thisJdkProcesses = outFolder.list((dir, name) -> name.startsWith(OutFiles.millWorker() + jvmHomeEncoding));

        int processLimit = 5;
        if (totalProcesses != null) {
            if (thisJdkProcesses != null) {
                processLimit -= Math.min(totalProcesses.length - thisJdkProcesses.length, 5);
            } else {
                processLimit -= Math.min(totalProcesses.length, 5);
            }
        }
        return processLimit;
    }

    /**
     * @deprecated Use {@link Util#md5hex(String)} instead. (Deprecated since after Mill 0.10.0)
     */
    public static String md5hex(String str) throws NoSuchAlgorithmException {
        return Util.md5hex(str);
    }

}
