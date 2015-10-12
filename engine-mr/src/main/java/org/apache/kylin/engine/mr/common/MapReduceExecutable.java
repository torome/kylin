/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.apache.kylin.engine.mr.common;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.Cluster;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobID;
import org.apache.hadoop.mapreduce.JobStatus;
import org.apache.hadoop.util.ToolRunner;
import org.apache.hadoop.yarn.conf.HAUtil;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.util.RMHAUtils;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.util.ClassUtil;
import org.apache.kylin.job.constant.ExecutableConstants;
import org.apache.kylin.job.constant.JobStepStatusEnum;
import org.apache.kylin.job.exception.ExecuteException;
import org.apache.kylin.job.execution.AbstractExecutable;
import org.apache.kylin.job.execution.ExecutableContext;
import org.apache.kylin.job.execution.ExecutableState;
import org.apache.kylin.job.execution.ExecuteResult;
import org.apache.kylin.job.execution.Output;

import com.google.common.base.Preconditions;

/**
 */
public class MapReduceExecutable extends AbstractExecutable {

    private static final String KEY_MR_JOB = "MR_JOB_CLASS";
    private static final String KEY_PARAMS = "MR_JOB_PARAMS";
    private static final String KEY_COUNTER_SAVEAS = "MR_COUNTER_SAVEAS";

    public static final String MAP_REDUCE_WAIT_TIME = "mapReduceWaitTime";

    public MapReduceExecutable() {
        super();
    }

    @Override
    protected void onExecuteStart(ExecutableContext executableContext) {
        final Output output = executableManager.getOutput(getId());
        if (output.getExtra().containsKey(START_TIME)) {
            final String mrJobId = output.getExtra().get(ExecutableConstants.MR_JOB_ID);
            if (mrJobId == null) {
                executableManager.updateJobOutput(getId(), ExecutableState.RUNNING, null, null);
                return;
            }
            try {
                Job job = new Cluster(new Configuration()).getJob(JobID.forName(mrJobId));
                if (job.getJobState() == JobStatus.State.FAILED) {
                    //remove previous mr job info
                    super.onExecuteStart(executableContext);
                } else {
                    executableManager.updateJobOutput(getId(), ExecutableState.RUNNING, null, null);
                }
            } catch (IOException e) {
                logger.warn("error get hadoop status");
                super.onExecuteStart(executableContext);
            } catch (InterruptedException e) {
                logger.warn("error get hadoop status");
                super.onExecuteStart(executableContext);
            }
        } else {
            super.onExecuteStart(executableContext);
        }
    }

    @Override
    protected ExecuteResult doWork(ExecutableContext context) throws ExecuteException {
        final String mapReduceJobClass = getMapReduceJobClass();
        String params = getMapReduceParams();
        Preconditions.checkNotNull(mapReduceJobClass);
        Preconditions.checkNotNull(params);
        try {
            Job job;
            final Map<String, String> extra = executableManager.getOutput(getId()).getExtra();
            if (extra.containsKey(ExecutableConstants.MR_JOB_ID)) {
                job = new Cluster(new Configuration()).getJob(JobID.forName(extra.get(ExecutableConstants.MR_JOB_ID)));
                logger.info("mr_job_id:" + extra.get(ExecutableConstants.MR_JOB_ID + " resumed"));
            } else {
                final Constructor<? extends AbstractHadoopJob> constructor = ClassUtil.forName(mapReduceJobClass, AbstractHadoopJob.class).getConstructor();
                final AbstractHadoopJob hadoopJob = constructor.newInstance();
                hadoopJob.setAsync(true); // so the ToolRunner.run() returns right away
                logger.info("parameters of the MapReduceExecutable:");
                logger.info(params);
                String[] args = params.trim().split("\\s+");
                try {
                    //for async mr job, ToolRunner just return 0;
                    ToolRunner.run(hadoopJob, args);
                } catch (Exception ex) {
                    StringBuilder log = new StringBuilder();
                    logger.error("error execute " + this.toString(), ex);
                    StringWriter stringWriter = new StringWriter();
                    ex.printStackTrace(new PrintWriter(stringWriter));
                    log.append(stringWriter.toString()).append("\n");
                    log.append("result code:").append(2);
                    return new ExecuteResult(ExecuteResult.State.ERROR, log.toString());
                }
                job = hadoopJob.getJob();
            }
            final StringBuilder output = new StringBuilder();
            final HadoopCmdOutput hadoopCmdOutput = new HadoopCmdOutput(job, output);

            final String restStatusCheckUrl = getRestStatusCheckUrl(job, context.getConfig());
            if (restStatusCheckUrl == null) {
                logger.error("restStatusCheckUrl is null");
                return new ExecuteResult(ExecuteResult.State.ERROR, "restStatusCheckUrl is null");
            }
            String mrJobId = hadoopCmdOutput.getMrJobId();
            HadoopStatusChecker statusChecker = new HadoopStatusChecker(restStatusCheckUrl, mrJobId, output);
            JobStepStatusEnum status = JobStepStatusEnum.NEW;
            while (!isDiscarded()) {
                JobStepStatusEnum newStatus = statusChecker.checkStatus();
                if (status == JobStepStatusEnum.KILLED) {
                    executableManager.updateJobOutput(getId(), ExecutableState.ERROR, Collections.<String, String> emptyMap(), "killed by admin");
                    return new ExecuteResult(ExecuteResult.State.FAILED, "killed by admin");
                }
                if (status == JobStepStatusEnum.WAITING && (newStatus == JobStepStatusEnum.FINISHED || newStatus == JobStepStatusEnum.ERROR || newStatus == JobStepStatusEnum.RUNNING)) {
                    final long waitTime = System.currentTimeMillis() - getStartTime();
                    setMapReduceWaitTime(waitTime);
                }
                status = newStatus;
                executableManager.addJobInfo(getId(), hadoopCmdOutput.getInfo());
                if (status.isComplete()) {
                    final Map<String, String> info = hadoopCmdOutput.getInfo();
                    readCounters(hadoopCmdOutput, info);
                    executableManager.addJobInfo(getId(), info);

                    if (status == JobStepStatusEnum.FINISHED) {
                        return new ExecuteResult(ExecuteResult.State.SUCCEED, output.toString());
                    } else {
                        return new ExecuteResult(ExecuteResult.State.FAILED, output.toString());
                    }
                }
                Thread.sleep(context.getConfig().getYarnStatusCheckIntervalSeconds() * 1000);
            }
            //TODO kill discarded mr job using "hadoop job -kill " + mrJobId

            return new ExecuteResult(ExecuteResult.State.DISCARDED, output.toString());

        } catch (ReflectiveOperationException e) {
            logger.error("error getMapReduceJobClass, class name:" + getParam(KEY_MR_JOB), e);
            return new ExecuteResult(ExecuteResult.State.ERROR, e.getLocalizedMessage());
        } catch (Exception e) {
            logger.error("error execute " + this.toString(), e);
            return new ExecuteResult(ExecuteResult.State.ERROR, e.getLocalizedMessage());
        }
    }

    private void readCounters(final HadoopCmdOutput hadoopCmdOutput, final Map<String, String> info) {
        hadoopCmdOutput.updateJobCounter();
        info.put(ExecutableConstants.SOURCE_RECORDS_COUNT, hadoopCmdOutput.getMapInputRecords());
        info.put(ExecutableConstants.SOURCE_RECORDS_SIZE, hadoopCmdOutput.getHdfsBytesRead());
        info.put(ExecutableConstants.HDFS_BYTES_WRITTEN, hadoopCmdOutput.getHdfsBytesWritten());

        String saveAs = getParam(KEY_COUNTER_SAVEAS);
        if (saveAs != null) {
            String[] saveAsNames = saveAs.split(",");
            saveCounterAs(hadoopCmdOutput.getMapInputRecords(), saveAsNames, 0, info);
            saveCounterAs(hadoopCmdOutput.getHdfsBytesRead(), saveAsNames, 1, info);
            saveCounterAs(hadoopCmdOutput.getHdfsBytesWritten(), saveAsNames, 2, info);
        }
    }

    private void saveCounterAs(String counter, String[] saveAsNames, int i, Map<String, String> info) {
        if (saveAsNames.length > i && StringUtils.isBlank(saveAsNames[i]) == false) {
            info.put(saveAsNames[i].trim(), counter);
        }
    }

    private String getRestStatusCheckUrl(Job job, KylinConfig config) {
        final String yarnStatusCheckUrl = config.getYarnStatusCheckUrl();
        if (yarnStatusCheckUrl != null) {
            return yarnStatusCheckUrl;
        } else {
            logger.info(KylinConfig.KYLIN_JOB_YARN_APP_REST_CHECK_URL + " is not set, read from job configuration");
        }
        String rmWebHost = HAUtil.getConfValueForRMInstance(YarnConfiguration.RM_WEBAPP_ADDRESS, YarnConfiguration.DEFAULT_RM_WEBAPP_ADDRESS, job.getConfiguration());
        if(HAUtil.isHAEnabled(job.getConfiguration())) {
            YarnConfiguration conf = new YarnConfiguration(job.getConfiguration());
            String active = RMHAUtils.findActiveRMHAId(conf);
            rmWebHost = HAUtil.getConfValueForRMInstance(HAUtil.addSuffix(YarnConfiguration.RM_WEBAPP_ADDRESS, active), YarnConfiguration.DEFAULT_RM_WEBAPP_ADDRESS, conf);
        }
        if (StringUtils.isEmpty(rmWebHost)) {
            return null;
        }
        if (rmWebHost.startsWith("http://") || rmWebHost.startsWith("https://")) {
            //do nothing
        } else {
            rmWebHost = "http://" + rmWebHost;
        }
        logger.info("yarn.resourcemanager.webapp.address:" + rmWebHost);
        return rmWebHost + "/ws/v1/cluster/apps/${job_id}?anonymous=true";
    }

    public long getMapReduceWaitTime() {
        return getExtraInfoAsLong(MAP_REDUCE_WAIT_TIME, 0L);
    }

    public void setMapReduceWaitTime(long t) {
        addExtraInfo(MAP_REDUCE_WAIT_TIME, t + "");
    }

    public void setMapReduceJobClass(Class<? extends AbstractHadoopJob> clazzName) {
        setParam(KEY_MR_JOB, clazzName.getName());
    }

    public String getMapReduceJobClass() throws ExecuteException {
        return getParam(KEY_MR_JOB);
    }

    public void setMapReduceParams(String param) {
        setParam(KEY_PARAMS, param);
    }

    public String getMapReduceParams() {
        return getParam(KEY_PARAMS);
    }

    public String getCounterSaveAs() {
        return getParam(KEY_COUNTER_SAVEAS);
    }

    public void setCounterSaveAs(String value) {
        setParam(KEY_COUNTER_SAVEAS, value);
    }

}
