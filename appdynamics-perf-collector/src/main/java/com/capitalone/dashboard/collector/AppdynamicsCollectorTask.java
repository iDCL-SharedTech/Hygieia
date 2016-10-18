package com.capitalone.dashboard.collector;

import com.capitalone.dashboard.model.AppdynamicsApplication;
import com.capitalone.dashboard.model.AppdynamicsCollector;
import com.capitalone.dashboard.model.Performance;
import com.capitalone.dashboard.model.PerformanceMetric;
import com.capitalone.dashboard.model.PerformanceType;
import com.capitalone.dashboard.repository.AppDynamicsApplicationRepository;
import com.capitalone.dashboard.repository.AppdynamicsCollectorRepository;
import com.capitalone.dashboard.repository.BaseCollectorRepository;
import com.capitalone.dashboard.repository.PerformanceRepository;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class AppdynamicsCollectorTask extends CollectorTask<AppdynamicsCollector> {


    private final AppdynamicsCollectorRepository appdynamicsCollectorRepository;
    private final AppDynamicsApplicationRepository appDynamicsApplicationRepository;
    private final PerformanceRepository performanceRepository;
    private final AppdynamicsClient appdynamicsClient;
    private final AppdynamicsSettings appdynamicsSettings;




    @Autowired
    public AppdynamicsCollectorTask(TaskScheduler taskScheduler,
                                    AppdynamicsCollectorRepository appdynamicsCollectorRepository,
                                    AppDynamicsApplicationRepository appDynamicsApplicationRepository,
                                    PerformanceRepository performanceRepository,
                                    AppdynamicsSettings appdynamicsSettings,
                                    AppdynamicsClient appdynamicsClient) {
        super(taskScheduler, "Appdynamics");
        this.appdynamicsCollectorRepository = appdynamicsCollectorRepository;
        this.appDynamicsApplicationRepository = appDynamicsApplicationRepository;
        this.performanceRepository = performanceRepository;
        this.appdynamicsSettings = appdynamicsSettings;
        this.appdynamicsClient = appdynamicsClient;
    }

    @Override
    public AppdynamicsCollector getCollector() {
        return AppdynamicsCollector.prototype(appdynamicsSettings);
    }

    @Override
    public BaseCollectorRepository<AppdynamicsCollector> getCollectorRepository() {
        return appdynamicsCollectorRepository;
    }

    @Override
    public String getCron() {
        return appdynamicsSettings.getCron();
    }

    @Override
    public void collect(AppdynamicsCollector collector) {

        long start = System.currentTimeMillis();
        Set<ObjectId> udId = new HashSet<>();
        udId.add(collector.getId());
        List<AppdynamicsApplication> existingApps = appDynamicsApplicationRepository.findByCollectorIdIn(udId);
        List<AppdynamicsApplication> latestProjects = new ArrayList<>();

        logBanner(collector.getInstanceUrl());

        Set<AppdynamicsApplication> apps = appdynamicsClient.getApplications();
        latestProjects.addAll(apps);

        log("Fetched applications   " + ((apps != null) ? apps.size() : 0), start);

        addNewProjects(apps, existingApps, collector);

        refreshData(enabledApplications(collector));


        log("Finished", start);
    }




    private void refreshData(List<AppdynamicsApplication> apps) {
        long start = System.currentTimeMillis();
        int count = 0;

        for (AppdynamicsApplication app : apps) {
            List<PerformanceMetric> metrics = appdynamicsClient.getPerformanceMetrics(app);

            if (!CollectionUtils.isEmpty(metrics)) {
                Performance performance = new Performance();
                performance.setCollectorItemId(app.getId());
                performance.setTimestamp(System.currentTimeMillis());
                performance.setType(PerformanceType.ApplicationPerformance);
                performance.getMetrics().addAll(metrics);
                if (isNewPerformanceData(app, performance)) {
                    performanceRepository.save(performance);
                    count++;
                }
            }
        }
        log("Updated", start, count);
    }

    private List<AppdynamicsApplication> enabledApplications(AppdynamicsCollector collector) {
//        return appDynamicsApplicationRepository.findEnabledAppdynamicsApplications(collector.getId());
        return  appDynamicsApplicationRepository.findByCollectorIdAndEnabled(collector.getId(), true);
    }


    private void addNewProjects(Set<AppdynamicsApplication> allApps, List<AppdynamicsApplication> exisingApps, AppdynamicsCollector collector) {
        long start = System.currentTimeMillis();
        int count = 0;
        Set<AppdynamicsApplication> newApps = new HashSet<>();

        for (AppdynamicsApplication app : allApps) {
            if (!exisingApps.contains(app)) {
                app.setCollectorId(collector.getId());
                app.setAppDashboardUrl(String.format(appdynamicsSettings.getDashboardUrl(),app.getAppID()));
                app.setEnabled(false);
                newApps.add(app);
                count++;
            }
        }
        //save all in one shot
        if (!CollectionUtils.isEmpty(newApps)) {
            appDynamicsApplicationRepository.save(newApps);
        }
        log("New appplications: ", start, count);
    }

    private boolean isNewPerformanceData(AppdynamicsApplication appdynamicsApplication, Performance performance) {
        return performanceRepository.findByCollectorItemIdAndTimestamp(
                appdynamicsApplication.getId(), performance.getTimestamp()) == null;
    }
}