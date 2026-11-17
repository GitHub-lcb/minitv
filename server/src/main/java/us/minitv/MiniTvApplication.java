package us.minitv;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/** MiniTV API - Java 21 port of the original FastAPI service. */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@MapperScan(basePackages = "us.minitv", annotationClass = org.apache.ibatis.annotations.Mapper.class)
public class MiniTvApplication {

    public static void main(String[] args) {
        SpringApplication.run(MiniTvApplication.class, args);
    }
}
