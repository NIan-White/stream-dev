package com.bhz.util;

import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;

/**
 * @Package com.stream.utils.PaimonMinioUtils
 * @Author zhou.han
 * @Date 2025/4/7 19:54
 * @description: Paimon connection Minio Utils
 */
public class PaimonMinioUtils {

    public static void ExecCreateMinioCatalogAndDatabases(StreamTableEnvironment tenv,String catalogName,String databaseName){
        System.setProperty("HADOOP_USER_NAME","root");
        if (catalogName.length() == 0){
            catalogName = "minio_paimon_catalog";
        }
        tenv.executeSql("CREATE CATALOG "+ catalogName +"                             " +
                "WITH                                                                   " +
                "  (                                                                    " +
                "    'type' = 'paimon',                                                 " +
                "    'warehouse' = 's3://paimon-data/',                                 " +
                "    's3.endpoint' = 'http://10.160.60.16:9000',                         " +
                "    's3.access-key' = 'ZhjwTSyQSnxkof1P0zcw',                          " +
                "    's3.secret-key' = 'Rd6QDCmZ57Lxycdl6sNYIkwuLqQgtoDreQAbpNFr',      " +
                "    's3.connection.ssl.enabled' = 'false',                             " +
                "    's3.path.style.access' = 'true',                                   " +
                "    's3.impl' = 'org.apache.hadoop.fs.s3a.S3AFileSystem',              " +
                "    's3.aws.credentials.provider' = 'org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider' " +
                "  );");
        tenv.executeSql("use catalog "+catalogName+";");
        tenv.executeSql("create database if not exists "+databaseName+";");
    }
}
