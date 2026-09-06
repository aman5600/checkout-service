package com.example.checkout.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "checkout")
public class CheckoutProperties {

    private Duration reconcileWindow = Duration.ofSeconds(5);
    private Job job = new Job();
    private Refund refund = new Refund();
    private Gateway gateway = new Gateway();

    public Duration getReconcileWindow() { return reconcileWindow; }
    public void setReconcileWindow(Duration reconcileWindow) { this.reconcileWindow = reconcileWindow; }
    public Job getJob() { return job; }
    public void setJob(Job job) { this.job = job; }
    public Refund getRefund() { return refund; }
    public void setRefund(Refund refund) { this.refund = refund; }
    public Gateway getGateway() { return gateway; }
    public void setGateway(Gateway gateway) { this.gateway = gateway; }

    public static class Job {
        private Duration interval = Duration.ofSeconds(1);
        private int batchSize = 100;
        private Duration lease = Duration.ofSeconds(30);

        public Duration getInterval() { return interval; }
        public void setInterval(Duration interval) { this.interval = interval; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        public Duration getLease() { return lease; }
        public void setLease(Duration lease) { this.lease = lease; }
    }

    public static class Refund {
        /**
         * When to re-check an order the job failed. One check would only catch a
         * payment that settled at that exact moment; the tail catches the rest.
         */
        private List<Duration> verifySchedule = List.of(
                Duration.ofSeconds(10), Duration.ofMinutes(1), Duration.ofMinutes(10));
        private Duration interval = Duration.ofSeconds(2);
        private int batchSize = 100;
        private Duration lease = Duration.ofSeconds(30);

        public List<Duration> getVerifySchedule() { return verifySchedule; }
        public void setVerifySchedule(List<Duration> verifySchedule) { this.verifySchedule = verifySchedule; }
        public Duration getInterval() { return interval; }
        public void setInterval(Duration interval) { this.interval = interval; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        public Duration getLease() { return lease; }
        public void setLease(Duration lease) { this.lease = lease; }
    }

    public static class Gateway {
        private String webhookUrl = "http://localhost:8080/webhooks/payment";
        private String defaultScenario = "FAST";

        public String getWebhookUrl() { return webhookUrl; }
        public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }
        public String getDefaultScenario() { return defaultScenario; }
        public void setDefaultScenario(String defaultScenario) { this.defaultScenario = defaultScenario; }
    }
}
