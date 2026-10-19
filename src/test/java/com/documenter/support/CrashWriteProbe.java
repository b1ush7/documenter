package com.documenter.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.documenter.configuration.StorageProperties;
import com.documenter.enums.ChangeType;
import com.documenter.mapper.DocumentVersionMapper;
import com.documenter.mapper.FileAssetMapper;
import com.documenter.service.VersionWriter;
import com.documenter.service.impl.*;
import com.documenter.util.Digests;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.*;
import java.nio.file.*;

/** Only launched by the isolated crash test; pauses inside real storage/transaction code. */
public final class CrashWriteProbe {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]), signal=Path.of(args[1]); String phase=args[2];
        String url=System.getenv("DOCUMENTER_CRASH_DB_URL");
        if(url==null || !url.matches("jdbc:mariadb://(?:127\\.0\\.0\\.1|localhost):[0-9]+/documenter_it_[0-9a-f]{32}"))
            throw new IllegalArgumentException("Only an isolated local test database is allowed");
        var properties=new StorageProperties(); properties.getLocal().setRoot(root.toString());
        properties.getLocal().setTemp(root.resolveSibling("child-temp").toString());
        var storage=new LocalFileStorage(properties);
        if(phase.equals("storage")) {
            storage.store(new java.io.InputStream() {
                boolean written;
                @Override public int read() { throw new UnsupportedOperationException(); }
                @Override public int read(byte[] bytes,int offset,int length) throws java.io.IOException {
                    if(written) { pause(signal,"partial storage"); return -1; }
                    written=true; int count=Math.min(length,4096); java.util.Arrays.fill(bytes,offset,offset+count,(byte)42); return count;
                }
            },"docx");
            throw new AssertionError("Storage probe must be killed");
        }
        var datasource=new DriverManagerDataSource(url,System.getenv("DOCUMENTER_LIFECYCLE_DB_TEST_USER"),
                System.getenv("DOCUMENTER_LIFECYCLE_DB_TEST_PASSWORD"));
        var configuration=new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
        configuration.addMapper(FileAssetMapper.class); configuration.addMapper(DocumentVersionMapper.class);
        var factory=new MybatisSqlSessionFactoryBean(); factory.setDataSource(datasource); factory.setConfiguration(configuration);
        var sessions=new SqlSessionTemplate(factory.getObject()); var files=sessions.getMapper(FileAssetMapper.class);
        var service=new DocumentVersionServiceImpl(sessions.getMapper(DocumentVersionMapper.class),files,
                new FileServiceImpl(files,storage,properties,null,null,null),storage);
        var manager=new DataSourceTransactionManager(datasource); var proxy=new ProxyFactory(service);
        proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        var writer=(VersionWriter)proxy.getProxy();
        byte[] bytes=("crash candidate "+phase).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String key=storage.store(bytes,"docx");
        new TransactionTemplate(manager).executeWithoutResult(status->{
            writer.appendVersion(1L,1L,new VersionWriter.VersionContent(key,bytes.length,Digests.sha256Hex(bytes)),
                    ChangeType.MANUAL_EDIT,null,"crash boundary",1);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) { if(phase.equals("before")) pause(signal,key); }
                @Override public void afterCommit() { if(phase.equals("after")) pause(signal,key); }
            });
        });
        throw new AssertionError("Transaction probe must be killed");
    }
    private static void pause(Path signal,String value) {
        try { Files.writeString(signal,value); Thread.sleep(Long.MAX_VALUE); }
        catch(Exception failure) { throw new IllegalStateException(failure); }
    }
}
