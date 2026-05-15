package top.ljx.util;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import javax.mail.MessagingException;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class MailUtilsTest {
    @Autowired
    private MailUtils mailUtils;
    @Autowired
    private JavaMailSenderImpl mailSender;

    @Test
    void sendSimpleMail() {
        try {
            mailSender.testConnection();
             System.out.println("连接成功");
            mailUtils.sendSimpleMail("1127891526@qq.com", "测试", "测试");
        } catch (MessagingException e) {
            throw new RuntimeException(e);
        }
    }
}