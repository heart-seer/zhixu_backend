package com.zhixu.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

/**
 * OpenAPI 文档配置：/swagger-ui.html 为交互式页面，/v3/api-docs 为原始 JSON。
 */
@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI zhixuOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("知序 APP 后端接口")
                        .version("0.1.0")
                        .description("统一响应体：code=1 表示成功、code=0 表示失败；所有 id 以十进制字符串返回；"
                                + "除认证接口外均需在请求头携带 Authorization: Bearer <accessToken>。")
                        .contact(new Contact().name("heart-seer").email("1879181738@qq.com")))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("opaque")
                                .description("登录或注册接口返回的 accessToken，按 Bearer 令牌使用")))
                .tags(Arrays.asList(
                        new Tag().name("认证").description("注册、密码登录、刷新令牌与退出登录"),
                        new Tag().name("我的资料与偏好").description("个人档案与偏好设置"),
                        new Tag().name("任务").description("任务、步骤与时间块的增删改查"),
                        new Tag().name("日程").description("日历事件的增删改查"),
                        new Tag().name("消息与提取").description("消息导入、已读与提取草稿确认")));
    }
}
