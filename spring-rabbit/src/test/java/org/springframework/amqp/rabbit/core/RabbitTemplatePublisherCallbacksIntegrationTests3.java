/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.amqp.rabbit.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.junit.RabbitAvailable;
import org.springframework.amqp.rabbit.junit.RabbitAvailableCondition;
import org.springframework.amqp.rabbit.support.CorrelationData;
import org.springframework.amqp.utils.test.TestUtils;

import com.rabbitmq.client.Channel;

/**
 * @author Gary Russell
 *
 * @since 2.1
 *
 */
@RabbitAvailable(queues = RabbitTemplatePublisherCallbacksIntegrationTests3.QUEUE)
public class RabbitTemplatePublisherCallbacksIntegrationTests3 {

	public static final String QUEUE = "defer.close";

	@Test
	@Disabled
	public void testRepublishOnNackThreadNoExchange() throws Exception {
		CachingConnectionFactory cf = new CachingConnectionFactory(
				RabbitAvailableCondition.getBrokerRunning().getConnectionFactory());
		cf.setPublisherConfirms(true);
		final RabbitTemplate template = new RabbitTemplate(cf);
		final CountDownLatch confirmLatch = new CountDownLatch(2);
		template.setConfirmCallback((cd, a, c) -> {
			if (confirmLatch.getCount() == 2) {
				template.convertAndSend(QUEUE, ((MyCD) cd).payload); // deadlock creating new channel
			}
			confirmLatch.countDown();
		});
		template.convertAndSend("bad.exchange", "junk", "foo", new MyCD("foo"));
		assertThat(confirmLatch.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(template.receive(QUEUE, 10_000)).isNotNull();
	}

	@Test
	public void testDeferredChannelCache() throws Exception {
		final CachingConnectionFactory cf = new CachingConnectionFactory(
				RabbitAvailableCondition.getBrokerRunning().getConnectionFactory());
		cf.setPublisherReturns(true);
		cf.setPublisherConfirms(true);
		final RabbitTemplate template = new RabbitTemplate(cf);
		final CountDownLatch returnLatch = new CountDownLatch(1);
		final CountDownLatch confirmLatch = new CountDownLatch(1);
		final AtomicInteger cacheCount = new AtomicInteger();
		template.setConfirmCallback((cd, a, c) -> {
			cacheCount.set(TestUtils.getPropertyValue(cf, "cachedChannelsNonTransactional", List.class).size());
			confirmLatch.countDown();
		});
		template.setReturnCallback((m, r, rt, e, rk) -> {
			returnLatch.countDown();
		});
		template.setMandatory(true);
		Connection conn = cf.createConnection();
		Channel channel1 = conn.createChannel(false);
		Channel channel2 = conn.createChannel(false);
		channel1.close();
		channel2.close();
		conn.close();
		assertThat(TestUtils.getPropertyValue(cf, "cachedChannelsNonTransactional", List.class).size()).isEqualTo(2);
		template.convertAndSend("", QUEUE + "junk", "foo", new MyCD("foo"));
		assertThat(returnLatch.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(confirmLatch.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(cacheCount.get()).isEqualTo(1);
		cf.destroy();
	}

	private static class MyCD extends CorrelationData {

		private final String payload;

		MyCD(String payload) {
			this.payload = payload;
		}

	}

}
