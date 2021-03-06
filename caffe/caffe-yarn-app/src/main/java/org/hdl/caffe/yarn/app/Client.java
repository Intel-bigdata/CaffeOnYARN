/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.hdl.caffe.yarn.app;

import org.apache.commons.cli.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.yarn.api.protocolrecords.GetNewApplicationResponse;
import org.apache.hadoop.yarn.api.records.*;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.client.api.YarnClientApplication;
import org.apache.hadoop.yarn.client.util.YarnClientUtils;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import sun.misc.Signal;
import sun.misc.SignalHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;

@InterfaceAudience.Public
@InterfaceStability.Unstable
public class Client {

    private static final Log LOG = LogFactory.getLog(Client.class);

    private Configuration conf;
    private YarnClient yarnClient;
    private String appName = CaffeYarnConstants.APP_NAME;

    private int amPriority = 0;
    private String amQueue = "";
    private long amMemory = 300;
    private int amVCores = 1;

    private String appMasterJar = "";

    private final String appMasterMainClass;

    // Amt of memory to request for container where caffeProcessor will run
    private int containerMemory = 300;
    // Amt. of virtual cores to request for container where caffeProcessor server will run
    private int containerVirtualCores = 1;

    private String nodeLabelExpression = null;

    // log4j.properties file
    // if available, add to local resources and set into classpath
    private String log4jPropFile = "";

    private long attemptFailuresValidityInterval = -1;

    private Vector<CharSequence> containerRetryOptions = new Vector<>(5);

    // Command line options
    private Options opts;

    // Hardcoded path to custom log_properties
    private static final String log4jPath = "log4j.properties";

    private int processorNum;
    private String solver;
    private boolean train;
    private boolean feature;
    private String label;
    private String model;
    private String output;
    private int connection;

    private CaffeApplicationRpc appRpc = null;

    /**
     * @param args Command line arguments
     */
    public static void main(String[] args) {
        LOG.info("start main in appmaster!");
        boolean result = false;
        try {
            Client client = new Client();
            LOG.info("Initializing client");
            try {
                boolean doRun = client.init(args);
                if (!doRun) {
                    System.exit(0);
                }
            } catch (IllegalArgumentException e) {
                System.err.println(e.getLocalizedMessage());
                System.exit(-1);
            }
            result = client.run();
        } catch (Throwable t) {
            LOG.fatal("Error running Client", t);
            System.exit(1);
        }
        if (result) {
            LOG.info("Application completed successfully");
            System.exit(0);
        }
        LOG.error("Application failed to complete successfully");
        System.exit(2);
    }

    public Client(Configuration conf) throws Exception {
        this("org.hdl.caffe.yarn.app.ApplicationMaster", conf);
    }

    Client(String appMasterMainClass, Configuration conf) {
        this.conf = conf;
        this.appMasterMainClass = appMasterMainClass;
        yarnClient = YarnClient.createYarnClient();
        yarnClient.init(conf);
        opts = new Options();
        opts.addOption(CaffeApplication.OPT_CAFFE_APPNAME, true, "Application Name, Default value: caffe");
        opts.addOption(CaffeApplication.OPT_CAFFE_PRIORITY, true, "Application Priority. Default 0");
        opts.addOption(CaffeApplication.OPT_CAFFE_QUEUE, true, "RM Queue in which this application is to be submitted");
        opts.addOption("jar", true, "Jar file containing the applicationMaster");
        opts.addOption(CaffeApplication.OPT_CAFFE_MASTER_MEMORY, true, "Amount of memory in MB to be requested to run the application master");
        opts.addOption(CaffeApplication.OPT_CAFFE_MASTER_VCORES, true, "Amount of virtual cores to be requested to run the application master");
        opts.addOption(CaffeApplication.OPT_CAFFE_CONTAINER_MEMORY, true, "Amount of memory in MB to be requested to run a tensorflow worker");
        opts.addOption(CaffeApplication.OPT_CAFFE_CONTAINER_VCORES, true, "Amount of virtual cores to be requested to run a tensorflow worker");
        opts.addOption(CaffeApplication.OPT_CAFFE_LOG_PROPERTIES, true, "log4j.properties file");
        opts.addOption(CaffeApplication.OPT_CAFFE_ATTEMPT_FAILURES_VALIDITY_INTERVAL, true,
                "when attempt_failures_validity_interval in milliseconds is set to > 0," +
                        "the failure number will not take failures which happen out of " +
                        "the validityInterval into failure count. " +
                        "If failure count reaches to maxAppAttempts, " +
                        "the application will be failed.");
        opts.addOption(CaffeApplication.OPT_CAFFE_NODE_LABEL_EXPRESSION, true,
                "Node label expression to determine the nodes"
                        + " where all the containers of this application"
                        + " will be allocated, \"\" means containers"
                        + " can be allocated anywhere, if you don't specify the option,"
                        + " default node_label_expression of queue will be used.");
        opts.addOption(CaffeApplication.OPT_CAFFE_CONTAINER_RETRY_POLICY, true,
                "Retry policy when container fails to run, "
                        + "0: NEVER_RETRY, 1: RETRY_ON_ALL_ERRORS, "
                        + "2: RETRY_ON_SPECIFIC_ERROR_CODES");
        opts.addOption(CaffeApplication.OPT_CAFFE_CONTAINER_RETRY_ERROR_CODES, true,
                "When retry policy is set to RETRY_ON_SPECIFIC_ERROR_CODES, error "
                        + "codes is specified with this option, "
                        + "e.g. --container_retry_error_codes 1,2,3");
        opts.addOption(CaffeApplication.OPT_CAFFE_CONTAINER_MAX_RETRIES, true,
                "If container could retry, it specifies max retires");
        opts.addOption(CaffeApplication.OPT_CAFFE_CONTAINER_RETRY_INTERVAL, true,
                "Interval between each retry, unit is milliseconds");
        opts.addOption(CaffeApplication.OPT_CAFFE_PROCESSOR_NUM, true,
                "worker quantity of caffe");
        opts.addOption(CaffeApplication.OPT_CAFFE_PROCESSOR_SOLVER, true,
                "solver_configuration");
        opts.addOption(CaffeApplication.OPT_CAFFE_PROCESSOR_TRAIN, true,
                "training_mode");
        opts.addOption(CaffeApplication.OPT_CAFFE_PROCESSOR_FEATURES, true,
                "name_of_output_blobs");
        opts.addOption(CaffeApplication.OPT_CAFFE_PROCESSOR_LABEL, true,
                "name of label blobs to be included in features");
        opts.addOption(CaffeApplication.OPT_CAFFE_PROCESSOR_MODEL, true,
                "model path");
        opts.addOption(CaffeApplication.OPT_CAFFE_PROCESSOR_OUTPUT, true,
                "output path");
        opts.addOption(CaffeApplication.OPT_CAFFE_PROCESSOR_CONNECTION, true,
                "network mode");
    }

    /**
     */
    public Client() throws Exception {
        this(new YarnConfiguration());
    }

    /**
     * Parse command line options
     *
     * @param args Parsed command line options
     * @return Whether the init was successful to run the client
     * @throws ParseException
     */
    public boolean init(String[] args) throws ParseException {

        CommandLine cliParser = new GnuParser().parse(opts, args);

        if (args.length == 0) {
            throw new IllegalArgumentException("No args specified for client to initialize");
        }

        if (cliParser.hasOption("log_properties")) {
            String log4jPath = cliParser.getOptionValue("log_properties");
            try {
                Log4jPropertyHelper.updateLog4jConfiguration(Client.class, log4jPath);
            } catch (Exception e) {
                LOG.warn("Can not set up custom log4j properties. " + e);
            }
        }

        appName = cliParser.getOptionValue(CaffeApplication.OPT_CAFFE_APPNAME, "caffe");
        amPriority = Integer.parseInt(cliParser.getOptionValue(CaffeApplication.OPT_CAFFE_PRIORITY, "0"));
        amQueue = cliParser.getOptionValue(CaffeApplication.OPT_CAFFE_QUEUE, "default");
        amMemory = Integer.parseInt(cliParser.getOptionValue(CaffeApplication.OPT_CAFFE_MASTER_MEMORY, "300"));
        amVCores = Integer.parseInt(cliParser.getOptionValue(CaffeApplication.OPT_CAFFE_MASTER_VCORES, "1"));
        processorNum = Integer.parseInt(cliParser.getOptionValue(CaffeApplication.OPT_CAFFE_PROCESSOR_NUM, "1"));

        solver = cliParser.getOptionValue(CaffeApplication.OPT_CAFFE_PROCESSOR_SOLVER, "");
        train = cliParser.hasOption(CaffeApplication.OPT_CAFFE_PROCESSOR_TRAIN);
        feature = cliParser.hasOption(CaffeApplication.OPT_CAFFE_PROCESSOR_FEATURES);
        label = cliParser.getOptionValue(CaffeApplication.OPT_CAFFE_PROCESSOR_LABEL, "");
        model = cliParser.getOptionValue(CaffeApplication.OPT_CAFFE_PROCESSOR_MODEL, "");
        output = cliParser.getOptionValue(CaffeApplication.OPT_CAFFE_PROCESSOR_OUTPUT, "");
        connection = Integer.parseInt(cliParser.getOptionValue(CaffeApplication.OPT_CAFFE_PROCESSOR_CONNECTION, "2"));

        if (amMemory < 0) {
            throw new IllegalArgumentException("Invalid memory specified for application master, exiting."
                    + " Specified memory=" + amMemory);
        }
        if (amVCores < 0) {
            throw new IllegalArgumentException("Invalid virtual cores specified for application master, exiting."
                    + " Specified virtual cores=" + amVCores);
        }

        if (!cliParser.hasOption("jar")) {
            throw new IllegalArgumentException("No jar file specified for application master");
        }

        appMasterJar = cliParser.getOptionValue("jar");

        containerMemory = Integer.parseInt(cliParser.getOptionValue(CaffeApplication.OPT_CAFFE_CONTAINER_MEMORY, "4096"));
        containerVirtualCores = Integer.parseInt(cliParser.getOptionValue(CaffeApplication.OPT_CAFFE_CONTAINER_VCORES, "1"));


        if (containerMemory < 0 || containerVirtualCores < 0 || processorNum < 1) {
            throw new IllegalArgumentException("Invalid no. of containers or container memory/vcores specified,"
                    + " exiting."
                    + " Specified containerMemory=" + containerMemory
                    + ", containerVirtualCores=" + containerVirtualCores
                    + ", workers=" + processorNum);
        }

        nodeLabelExpression = cliParser.getOptionValue(CaffeApplication.OPT_CAFFE_NODE_LABEL_EXPRESSION, null);

        attemptFailuresValidityInterval =
                Long.parseLong(cliParser.getOptionValue(
                        CaffeApplication.OPT_CAFFE_ATTEMPT_FAILURES_VALIDITY_INTERVAL, "-1"));

        log4jPropFile = cliParser.getOptionValue(CaffeApplication.OPT_CAFFE_LOG_PROPERTIES, "");

        // Get container retry options
        if (cliParser.hasOption(CaffeApplication.OPT_CAFFE_CONTAINER_RETRY_POLICY)) {
            containerRetryOptions.add(CaffeApplication.makeOption(CaffeApplication.OPT_CAFFE_CONTAINER_RETRY_POLICY,
                    cliParser.getOptionValue(CaffeApplication.OPT_CAFFE_CONTAINER_RETRY_POLICY)));
        }
        if (cliParser.hasOption(CaffeApplication.OPT_CAFFE_CONTAINER_RETRY_ERROR_CODES)) {
            containerRetryOptions.add(CaffeApplication.makeOption(CaffeApplication.OPT_CAFFE_CONTAINER_RETRY_ERROR_CODES,
                    cliParser.getOptionValue(CaffeApplication.OPT_CAFFE_CONTAINER_RETRY_ERROR_CODES)));
        }
        if (cliParser.hasOption(CaffeApplication.OPT_CAFFE_CONTAINER_MAX_RETRIES)) {
            containerRetryOptions.add(CaffeApplication.makeOption(CaffeApplication.OPT_CAFFE_CONTAINER_MAX_RETRIES,
                    cliParser.getOptionValue(CaffeApplication.OPT_CAFFE_CONTAINER_MAX_RETRIES)));
        }
        if (cliParser.hasOption(CaffeApplication.OPT_CAFFE_CONTAINER_RETRY_INTERVAL)) {
            containerRetryOptions.add(CaffeApplication.makeOption(CaffeApplication.OPT_CAFFE_CONTAINER_RETRY_INTERVAL,
                    cliParser.getOptionValue(CaffeApplication.OPT_CAFFE_CONTAINER_RETRY_INTERVAL)));
        }

        return true;
    }

    private String copyLocalFileToDfs(FileSystem fs, String appId, String srcFilePath, String dstFileName) throws IOException {
        String suffix = CaffeYarnConstants.APP_NAME + "/" + appId + "/" + dstFileName;
        Path dst = new Path(fs.getHomeDirectory(), suffix);
        if (srcFilePath != null) {
            fs.copyFromLocalFile(new Path(srcFilePath), dst);
        }
        LOG.info("Copy " + srcFilePath + " to " + dst.toString());
        return dst.toString();
    }

    /**
     * Main run function for the client
     *
     * @return true if application completed successfully
     * @throws IOException
     * @throws YarnException
     */
    public boolean run() throws IOException, YarnException {

        yarnClient.start();

        YarnClusterMetrics clusterMetrics = yarnClient.getYarnClusterMetrics();
        LOG.info("Got Cluster metric info from ASM"
                + ", numNodeManagers=" + clusterMetrics.getNumNodeManagers());

        List<NodeReport> clusterNodeReports = yarnClient.getNodeReports(
                NodeState.RUNNING);
        LOG.info("Got Cluster node info from ASM");
        for (NodeReport node : clusterNodeReports) {
            LOG.info("Got node report from ASM for"
                    + ", nodeId=" + node.getNodeId()
                    + ", nodeAddress=" + node.getHttpAddress()
                    + ", nodeRackName=" + node.getRackName()
                    + ", nodeNumContainers=" + node.getNumContainers());
        }

        QueueInfo queueInfo = yarnClient.getQueueInfo(this.amQueue);
        LOG.info("Queue info"
                + ", queueName=" + queueInfo.getQueueName()
                + ", queueCurrentCapacity=" + queueInfo.getCurrentCapacity()
                + ", queueMaxCapacity=" + queueInfo.getMaximumCapacity()
                + ", queueApplicationCount=" + queueInfo.getApplications().size()
                + ", queueChildQueueCount=" + queueInfo.getChildQueues().size());

        List<QueueUserACLInfo> listAclInfo = yarnClient.getQueueAclsInfo();
        for (QueueUserACLInfo aclInfo : listAclInfo) {
            for (QueueACL userAcl : aclInfo.getUserAcls()) {
                LOG.info("User ACL Info for Queue"
                        + ", queueName=" + aclInfo.getQueueName()
                        + ", userAcl=" + userAcl.name());
            }
        }

        // Get a new application id
        YarnClientApplication app = yarnClient.createApplication();
        GetNewApplicationResponse appResponse = app.getNewApplicationResponse();

        long maxMem = appResponse.getMaximumResourceCapability().getMemorySize();
        LOG.info("Max mem capability of resources in this cluster " + maxMem);

        if (amMemory > maxMem) {
            LOG.info("AM memory specified above max threshold of cluster. Using max value."
                    + ", specified=" + amMemory
                    + ", max=" + maxMem);
            amMemory = maxMem;
        }

        int maxVCores = appResponse.getMaximumResourceCapability().getVirtualCores();
        LOG.info("Max virtual cores capability of resources in this cluster " + maxVCores);

        if (amVCores > maxVCores) {
            LOG.info("AM virtual cores specified above max threshold of cluster. "
                    + "Using max value." + ", specified=" + amVCores
                    + ", max=" + maxVCores);
            amVCores = maxVCores;
        }

        ApplicationSubmissionContext appContext = app.getApplicationSubmissionContext();
        ApplicationId appId = appContext.getApplicationId();

        appContext.setApplicationName(appName);

        if (attemptFailuresValidityInterval >= 0) {
            appContext.setAttemptFailuresValidityInterval(attemptFailuresValidityInterval);
        }

        Set<String> tags = new HashSet<String>();
        appContext.setApplicationTags(tags);

        Map<String, LocalResource> localResources = new HashMap<String, LocalResource>();

        CaffeAmContainer CaffeAmContainer = new CaffeAmContainer(this);

        // Copy the application jar to the filesystem
        FileSystem fs = FileSystem.get(conf);
        String dstJarPath = copyLocalFileToDfs(fs, appId.toString(), appMasterJar, CaffeContainer.SERVER_JAR_PATH);
        CaffeAmContainer.addToLocalResources(fs, new Path(dstJarPath), CaffeAmContainer.APPMASTER_JAR_PATH, localResources);

        Map<String, String> env = CaffeAmContainer.setJavaEnv(conf);
        env.put("LD_LIBRARY_PATH", "/root/CaffeOnSpark/caffe-public/distribute/lib:/root/CaffeOnSpark/caffe-distri/distribute/lib");

        if (null != nodeLabelExpression) {
            appContext.setNodeLabelExpression(nodeLabelExpression);
        }

        StringBuilder command = CaffeAmContainer.makeCommands(amMemory, appMasterMainClass, containerMemory, containerVirtualCores,
                processorNum, dstJarPath, containerRetryOptions, train, solver, feature, label, model, output, connection);

        LOG.info("AppMaster command: " + command.toString());
        List<String> commands = new ArrayList<String>();
        commands.add(command.toString());

        ContainerLaunchContext amContainer = ContainerLaunchContext.newInstance(
                localResources, env, commands, null, null, null);

        Resource capability = Resource.newInstance(amMemory, amVCores);
        appContext.setResource(capability);

        // Service data is a binary blob that can be passed to the application
        // Not needed in this scenario
        // amContainer.setServiceData(serviceData);

        // Setup security tokens
        if (UserGroupInformation.isSecurityEnabled()) {
            // Note: Credentials class is marked as LimitedPrivate for HDFS and MapReduce
            Credentials credentials = new Credentials();
            String tokenRenewer = YarnClientUtils.getRmPrincipal(conf);
            if (tokenRenewer == null || tokenRenewer.length() == 0) {
                throw new IOException(
                        "Can't get Master Kerberos principal for the RM to use as renewer");
            }

            // For now, only getting tokens for the default file-system.
            final Token<?> tokens[] =
                    fs.addDelegationTokens(tokenRenewer, credentials);
            if (tokens != null) {
                for (Token<?> token : tokens) {
                    LOG.info("Got dt for " + fs.getUri() + "; " + token);
                }
            }
            DataOutputBuffer dob = new DataOutputBuffer();
            credentials.writeTokenStorageToStream(dob);
            ByteBuffer fsTokens = ByteBuffer.wrap(dob.getData(), 0, dob.getLength());
            amContainer.setTokens(fsTokens);
        }

        appContext.setAMContainerSpec(amContainer);

        // Set the priority for the application master
        Priority pri = Priority.newInstance(amPriority);
        appContext.setPriority(pri);

        appContext.setQueue(amQueue);

        LOG.info("Submitting application to ASM");

        yarnClient.submitApplication(appContext);
        handleSignal(appId);
        return monitorApplication(appId);

    }

    private boolean isEmptyString(String s) {
        return s == null || s.equals("");
    }

    /**
     * Monitor the submitted application for completion.
     *
     * @param appId Application Id of application to be monitored
     * @return true if application completed successfully
     * @throws YarnException
     * @throws IOException
     */
    private boolean monitorApplication(ApplicationId appId)
            throws YarnException, IOException {

        while (true) {

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                LOG.debug("Thread sleep in monitoring loop interrupted");
            }

            ApplicationReport report = yarnClient.getApplicationReport(appId);

            LOG.info("Got application report from ASM for"
                    + ", appId=" + appId.getId()
                    + ", clientToAMToken=" + report.getClientToAMToken()
                    + ", appDiagnostics=" + report.getDiagnostics()
                    + ", appMasterHost=" + report.getHost()
                    + ", appQueue=" + report.getQueue()
                    + ", appMasterRpcPort=" + report.getRpcPort()
                    + ", appStartTime=" + report.getStartTime()
                    + ", yarnAppState=" + report.getYarnApplicationState().toString()
                    + ", caffeAppFinalState=" + report.getFinalApplicationStatus().toString()
                    + ", appTrackingUrl=" + report.getTrackingUrl()
                    + ", appUser=" + report.getUser());

            YarnApplicationState state = report.getYarnApplicationState();
            FinalApplicationStatus caffeStatus = report.getFinalApplicationStatus();

            if (YarnApplicationState.RUNNING == state) {
                if (appRpc == null) {
                    String hostname = report.getHost();
                    int port = report.getRpcPort();
                    LOG.info("Application master rpc host: " + hostname + "; port: " + port);
                    appRpc = new CaffeApplicationRpcClient(hostname, port).getRpc();
                }
            }

            if (YarnApplicationState.FINISHED == state) {
                if (FinalApplicationStatus.SUCCEEDED == caffeStatus) {
                    LOG.info("Application has completed successfully. Breaking monitoring loop");
                    return true;
                } else {
                    LOG.info("Application did finished unsuccessfully."
                            + " YarnState=" + state.toString() + ", appFinalState=" + caffeStatus.toString()
                            + ". Breaking monitoring loop");
                    return false;
                }
            } else if (YarnApplicationState.KILLED == state
                    || YarnApplicationState.FAILED == state) {
                LOG.info("Application did not finish."
                        + " YarnState=" + state.toString() + ", appFinalState=" + caffeStatus.toString()
                        + ". Breaking monitoring loop");
                return false;
            }
        }

    }

    private class ClientSignalHandler implements SignalHandler {

        public static final String SIG_INT = "INT";
        private ApplicationId appId = null;

        public ClientSignalHandler(ApplicationId appId) {
            this.appId = appId;
        }

        @Override
        public void handle(Signal signal) {
            if (signal.getName().equals(SIG_INT)) {
                try {
                    forceKillApplication(appId);
                } catch (YarnException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                System.exit(0);
            }
        }
    }

    private void handleSignal(ApplicationId appId) {
        ClientSignalHandler sigHandler = new ClientSignalHandler(appId);
        Signal.handle(new Signal(ClientSignalHandler.SIG_INT), sigHandler);
    }

    /**
     * Kill a submitted application by sending a call to the ASM
     *
     * @param appId Application Id to be killed.
     * @throws YarnException
     * @throws IOException
     */
    private void forceKillApplication(ApplicationId appId)
            throws YarnException, IOException {

        // Response can be ignored as it is non-null on success or
        // throws an exception in case of failures
        yarnClient.killApplication(appId);
    }

}
