package io.mycat.sqlhandler;

import com.alibaba.druid.util.JdbcUtils;
import groovy.transform.ToString;
import io.mycat.MetaClusterCurrent;
import io.mycat.MycatException;
import io.mycat.Partition;
import io.mycat.ScheduleUtil;
import io.mycat.api.collector.RowBaseIterator;
import io.mycat.beans.mycat.ResultSetBuilder;
import io.mycat.config.DatasourceConfig;
import io.mycat.config.MycatRouterConfig;
import io.mycat.replica.ReplicaSelectorManager;
import io.reactivex.rxjava3.annotations.NonNull;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Observer;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.functions.Cancellable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import lombok.Data;
import lombok.Getter;
import lombok.SneakyThrows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static io.mycat.vertxmycat.JdbcMySqlConnection.setStreamFlag;

public class MigrateUtil {
    private static final Logger LOGGER = LoggerFactory.getLogger(MigrateUtil.class);
    public static CopyOnWriteArrayList<MigrateScheduler> schedulers = new CopyOnWriteArrayList();
    public final static AtomicInteger IDS = new AtomicInteger();

    public static RowBaseIterator list() {
        List<MigrateScheduler> schedulers = MigrateUtil.schedulers;
        return show(schedulers);
    }

    public static RowBaseIterator show(MigrateScheduler scheduler) {
        return show(Collections.singletonList(scheduler));
    }

    public static RowBaseIterator show(List<MigrateScheduler> schedulers) {
        ResultSetBuilder builder = ResultSetBuilder.create();
        builder.addColumnInfo("ID", JDBCType.VARCHAR);
        builder.addColumnInfo("NAME", JDBCType.VARCHAR);
        builder.addColumnInfo("PROCESS", JDBCType.VARCHAR);
        builder.addColumnInfo("COMPLETE", JDBCType.INTEGER);
        builder.addColumnInfo("INFO", JDBCType.VARCHAR);
        builder.addColumnInfo("ERROR", JDBCType.VARCHAR);
        builder.addColumnInfo("START_TIME", JDBCType.TIMESTAMP);
        builder.addColumnInfo("END_TIME", JDBCType.TIMESTAMP);
        builder.addColumnInfo("INPUT_ROW", JDBCType.BIGINT);
        builder.addColumnInfo("OUTPUT_ROW", JDBCType.BIGINT);
        for (MigrateScheduler scheduler : schedulers) {
            String id = scheduler.getId();
            String name = scheduler.getName();
            int complete = scheduler.getFuture().isComplete() ? 1 : 0;
            String process = scheduler.computeProcess() * 100 + "%";
            String info = scheduler.toString();
            builder.addObjectRowPayload(
                    new Object[]{
                            id, name, process, complete, info, scheduler.getFuture().cause(),
                            scheduler.getStartTime(), scheduler.getEndTime(),
                            scheduler.computeInputRow(),
                            scheduler.getOutput().getRow().get()
                    }
            );
        }
        return builder.build();
    }

    public static boolean stop(String id) {
        Optional<MigrateScheduler> optional = schedulers.stream().filter(i -> id.equals(id)).findFirst();
        optional.ifPresent(
                new Consumer<MigrateScheduler>() {
                    @Override
                    public void accept(MigrateScheduler migrateScheduler) {
                        migrateScheduler.stop();
                    }
                }
        );
        return optional.isPresent();
    }

    public static MigrateScheduler register(String name,
                                            List<MigrateJdbcInput> inputs,
                                            MigrateJdbcOutput output,
                                            MigrateControllerImpl controller) {
        MigrateScheduler scheduler = MigrateScheduler.of(name, inputs, output, controller);
        schedulers.add(scheduler);
        controller.getFuture().onSuccess(event -> {
            RowBaseIterator list = list();
            LOGGER.info("----------------------------Migration-INFO-----------------------------------------------");
            List<Map<String, Object>> resultSetMap = list.getResultSetMap();
            for (Map<String, Object> stringObjectMap : resultSetMap) {
                LOGGER.info(stringObjectMap.toString());
            }
            LOGGER.info("-----------------------------------------------------------------------------------------");
            ScheduleUtil.getTimerFuture(() -> {
                schedulers.remove(scheduler);
                LOGGER.info("----------------------------Migration-REMOVE-{}---------------------------------------",
                        scheduler.getName());
            }, 1, TimeUnit.HOURS);
        });
        return scheduler;
    }

    @Data
    @ToString
    public static class MigrateJdbcInput {
        long count;
        final AtomicLong row = new AtomicLong();
    }

    @Data
    @ToString
    public static class MigrateJdbcOutput {
        String username;
        String password;
        String url;
        String insertTemplate;
        final AtomicLong row = new AtomicLong();
    }

    @Getter
    @ToString
    public static class MigrateScheduler {
        String id;
        String name;
        List<MigrateJdbcInput> inputs;
        MigrateJdbcOutput output;
        Future<Void> future;
        LocalDateTime startTime = LocalDateTime.now();
        LocalDateTime endTime;
        private MigrateControllerImpl controller;

        public MigrateScheduler(String name,
                                List<MigrateJdbcInput> inputs,
                                MigrateJdbcOutput output,
                                MigrateControllerImpl controller) {
            this.controller = controller;
            this.id = UUID.randomUUID().toString();
            this.name = name;
            this.inputs = inputs;
            this.output = output;
            this.future = this.controller.getFuture();

            future.onComplete(event -> MigrateScheduler.this.endTime = LocalDateTime.now());
        }

        public double computeProcess() {
            long total = computeInputRow();
            long nowOutputRow = output.getRow().get();
            if (total == nowOutputRow) {
                return 1;
            }
            return nowOutputRow * 1.0 / total;
        }

        public long computeInputRow() {
            return inputs.stream().mapToLong(i -> i.getCount()).sum();
        }

        public static MigrateScheduler of(String name,
                                          List<MigrateJdbcInput> inputs,
                                          MigrateJdbcOutput output,
                                          MigrateControllerImpl controller) {
            return new MigrateScheduler(name, inputs, output, controller);
        }

        @Override
        public String toString() {
            return "MigrateScheduler{" +
                    "name='" + name + '\'' +
                    ", inputs=" + inputs +
                    ", output=" + output +
                    ", future=" + future +
                    ", startTime=" + startTime +
                    ", endTime=" + endTime +
                    '}';
        }

        public void stop() {
            controller.stop();
        }
    }

    @SneakyThrows
    public static Observable<Object[]> read(MigrateUtil.MigrateJdbcInput migrateJdbcInput, Partition backend) {
        MycatRouterConfig routerConfig = MetaClusterCurrent.wrapper(MycatRouterConfig.class);
        ReplicaSelectorManager replicaSelectorRuntime = MetaClusterCurrent.wrapper(ReplicaSelectorManager.class);

        String targetName = backend.getTargetName();
        String tableName = backend.getTable();
        String schemaName = backend.getSchema();

        String datasourceName = replicaSelectorRuntime.getDatasourceNameByReplicaName(targetName, true, null);

        List<DatasourceConfig> datasources = routerConfig.getDatasources();
        DatasourceConfig datasourceConfig = datasources.stream().filter(i -> i.getName().equals(datasourceName)).findFirst().orElseThrow((Supplier<Throwable>) () -> {
            MycatException mycatException = new MycatException("can not found datasource " + datasourceName);
            LOGGER.error("", mycatException);
            return mycatException;
        });

        return read(migrateJdbcInput, tableName, schemaName, datasourceConfig.getUrl(), datasourceConfig.getUser(), datasourceConfig.getPassword());
    }

    @SneakyThrows
    public static Observable<Object[]> read(MigrateJdbcInput migrateJdbcInput, String tableName, String schemaName, String url, String user, String password) {
        String queryCountSql = "select count(1) from `" + schemaName + "`.`" + tableName + "`";
        try (Connection connection = DriverManager.getConnection(url, user, password);) {
            Number countO = (Number) JdbcUtils.executeQuery(connection, queryCountSql, Collections.emptyList()).get(0).values().iterator().next();
            migrateJdbcInput.setCount(countO.longValue());
        }
        String querySql = "select * from `" + schemaName + "`.`" + tableName + "`";
        return read(migrateJdbcInput, url, user, password, querySql);
    }

    public static Observable<Object[]> read(MigrateJdbcInput migrateJdbcInput, String url, String user, String password, String querySql) {
        Observable<Object[]> objectObservable = Observable.create(emitter -> {
            try (Connection connection = DriverManager.getConnection(url, user, password);) {
                emitter.setCancellable(new Cancellable() {
                    @Override
                    public void cancel() throws Throwable {
                        connection.close();
                        LOGGER.info("close " + migrateJdbcInput);
                    }
                });
                Statement statement = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                setStreamFlag(statement);
                ResultSet resultSet = statement.executeQuery(querySql);
                ResultSetMetaData metaData = resultSet.getMetaData();
                int columnCount = metaData.getColumnCount();
                while (resultSet.next()) {
                    migrateJdbcInput.getRow().getAndIncrement();
                    Object[] objects = new Object[columnCount];
                    for (int i = 0; i < columnCount; i++) {
                        objects[i] = resultSet.getObject(i + 1);
                    }
                    emitter.onNext(objects);
                }
                emitter.onComplete();
            } catch (Throwable throwable) {
                emitter.onError(throwable);
            }
        });
        return objectObservable;
    }

    public interface MigrateController {

        public Future<Void> getFuture();

        public void stop();

    }

    public static class MigrateControllerImpl implements MigrateController, Observer<List<Object[]>> {

        Connection mycatConnection = null;
        Disposable disposable;
        Promise<Void> promise = Promise.promise();

        MigrateJdbcOutput output;

        public MigrateControllerImpl(MigrateJdbcOutput output) {
            this.output = output;
        }

        public Future<Void> getFuture() {
            return promise.future();
        }

        public void stop() {
            if (!disposable.isDisposed()) {
                disposable.dispose();
            }
            if (mycatConnection != null) {
                JdbcUtils.close(mycatConnection);
            }
            promise.tryComplete();
        }


        @Override
        public void onSubscribe(@NonNull Disposable d) {
            this.disposable = d;
        }

        @Override
        public void onNext(@NonNull List<Object[]> objects) {
            try {
                if (this.mycatConnection == null) {
                    this.mycatConnection = DriverManager.getConnection(this.output.getUrl(), this.output.getUsername(), this.output.getPassword());
                }
                PreparedStatement preparedStatement = this.mycatConnection.prepareStatement(this.output.getInsertTemplate());
                for (Object[] object : objects) {
                    int i = 1;
                    for (Object o : object) {
                        preparedStatement.setObject(i, o);
                        i++;
                    }
                    preparedStatement.addBatch();
                }
                preparedStatement.executeBatch();
                output.row.addAndGet(objects.size());
                preparedStatement.clearParameters();
            } catch (Exception e) {
                onError(e);
            }
        }

        @Override
        public void onError(@NonNull Throwable e) {
            this.disposable.dispose();
            if (this.mycatConnection != null) {
                JdbcUtils.close(this.mycatConnection);
            }
            promise.tryFail(e);
        }

        @Override
        public void onComplete() {
            this.disposable.dispose();
            if (this.mycatConnection != null) {
                JdbcUtils.close(this.mycatConnection);
            }
            promise.tryComplete();
        }
    }

    public static class MigrateControllerGroup implements MigrateController {

        @Override
        public Future<Void> getFuture() {
            return null;
        }

        @Override
        public void stop() {

        }
    }

    public static MigrateControllerImpl write(MigrateJdbcOutput output, Observable<Object[]> concat) {
        Observable<@NonNull List<Object[]>> buffer = concat.subscribeOn(Schedulers.computation())
                .buffer(10000).subscribeOn(Schedulers.io());
        MigrateControllerImpl migrateController = new MigrateControllerImpl(output);
        buffer.subscribe(migrateController);
        return migrateController;
    }

}