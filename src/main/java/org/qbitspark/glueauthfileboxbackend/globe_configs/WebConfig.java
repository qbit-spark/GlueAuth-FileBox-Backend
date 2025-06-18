package org.qbitspark.glueauthfileboxbackend.globe_configs;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;


//Bro we got another shit here never sniff here.........
@Configuration
public class WebConfig implements WebMvcConfigurer {

//    @Value("${file.upload-dir}")
//    private String uploadDir;

//    @Override
//    public void addResourceHandlers(ResourceHandlerRegistry registry) {
//        registry.addResourceHandler("/images/**")
//                .addResourceLocations("file:" + uploadDir + "/");
//    }


    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins("*") // Allow all origins for testing; restrict in production
                //.allowedOrigins("https://kfz.pos-service.store")
                .allowedMethods("*")
                .allowedHeaders("*")
                .allowCredentials(false); // Set to true if using cookies or HTTP authentication
    }
}

