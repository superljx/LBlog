package top.ljx.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import javax.mail.internet.MimeMessage;
import java.util.Map;

/**
 * @Description: 邮件工具类
 * @Author: Naccl
 * @Date: 2020-10-10
 */
@Component
public class MailUtils {
	private static final Logger log = LoggerFactory.getLogger(MailUtils.class);
	@Autowired
	private JavaMailSender javaMailSender;
	@Autowired
	private MailProperties mailProperties;
	@Autowired
	TemplateEngine templateEngine;

	@Async
	public void sendSimpleMail(String toAccount, String subject, String content) {
		try {
			SimpleMailMessage message = new SimpleMailMessage();
			message.setFrom(mailProperties.getUsername());
			message.setTo(toAccount);
			message.setSubject(subject);
			message.setText(content);
			javaMailSender.send(message);
		} catch (Exception e) {
			log.error("Failed to send simple mail to [{}] with subject [{}]", toAccount, subject, e);
		}
	}

	@Async
	public void sendHtmlTemplateMail(Map<String, Object> map, String toAccount, String subject, String template) {
		try {
			MimeMessage mimeMessage = javaMailSender.createMimeMessage();
			MimeMessageHelper messageHelper = new MimeMessageHelper(mimeMessage);
			Context context = new Context();
			context.setVariables(map);
			String process = templateEngine.process(template, context);
			messageHelper.setFrom(mailProperties.getUsername());
			messageHelper.setTo(toAccount);
			messageHelper.setSubject(subject);
			messageHelper.setText(process, true);
			javaMailSender.send(mimeMessage);
		} catch (Exception e) {
			log.error("Failed to send template mail [{}] to [{}] with subject [{}]", template, toAccount, subject, e);
		}
	}
}
