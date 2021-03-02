/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.slack;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import com.slack.api.Slack;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.response.conversations.ConversationsHistoryResponse;
import com.slack.api.methods.response.conversations.ConversationsListResponse;
import com.slack.api.model.Conversation;
import com.slack.api.model.Message;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.RuntimeCamelException;
import org.apache.camel.support.ScheduledBatchPollingConsumer;
import org.apache.camel.util.CastUtils;
import org.apache.camel.util.ObjectHelper;

public class SlackConsumer extends ScheduledBatchPollingConsumer {

    private final SlackEndpoint slackEndpoint;
    private Slack slack;
    private String timestamp;
    private String channelId;

    public SlackConsumer(SlackEndpoint endpoint, Processor processor) {
        super(endpoint, processor);
        this.slackEndpoint = endpoint;
    }

    @Override
    protected void doStart() throws Exception {
        this.slack = Slack.getInstance();
        this.channelId = getChannelId(slackEndpoint.getChannel(), null);
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception {
        super.doStop();
        if (slack != null) {
            slack.close();
        }
    }

    @Override
    protected int poll() throws Exception {
        // Maximum limit is 1000. Slack recommends no more than 200 results at a time.
        // https://api.slack.com/methods/conversations.history
        // We set the limit to 1 the first call to set the timestamp of the last message of the history
        ConversationsHistoryResponse response = slack.methods(slackEndpoint.getToken()).conversationsHistory(req -> req
                .channel(channelId)
                .oldest(timestamp)
                .limit(timestamp != null ? Integer.parseInt(slackEndpoint.getMaxResults()) : 1));

        if (!response.isOk()) {
            throw new RuntimeCamelException("API request conversations.history to Slack failed: " + response);
        }

        Queue<Exchange> exchanges = createExchanges(response.getMessages());
        return processBatch(CastUtils.cast(exchanges));
    }

    private Queue<Exchange> createExchanges(final List<Message> list) {
        Queue<Exchange> answer = new LinkedList<>();
        if (ObjectHelper.isNotEmpty(list)) {
            if (slackEndpoint.isNaturalOrder()) {
                for (int i = list.size() - 1; i >= 0; i--) {
                    Message message = list.get(i);
                    if (i == 0) {
                        timestamp = message.getTs();
                    }
                    Exchange exchange = createExchange(message);
                    answer.add(exchange);
                }
            } else {
                for (int i = 0; i < list.size(); i++) {
                    Message message = list.get(i);
                    if (i == 0) {
                        timestamp = message.getTs();
                    }
                    Exchange exchange = createExchange(message);
                    answer.add(exchange);
                }
            }
        }
        return answer;
    }

    @Override
    public int processBatch(Queue<Object> exchanges) throws Exception {
        int total = exchanges.size();

        for (int index = 0; index < total && isBatchAllowed(); index++) {
            // only loop if we are started (allowed to run)
            final Exchange exchange = ObjectHelper.cast(Exchange.class, exchanges.poll());
            // add current index and total as properties
            exchange.setProperty(Exchange.BATCH_INDEX, index);
            exchange.setProperty(Exchange.BATCH_SIZE, total);
            exchange.setProperty(Exchange.BATCH_COMPLETE, index == total - 1);

            // update pending number of exchanges
            pendingExchanges = total - index - 1;

            getAsyncProcessor().process(exchange, doneSync -> {
                // noop
            });
        }

        return total;
    }

    private String getChannelId(final String channel, final String cursor) {
        try {
            // Maximum limit is 1000. Slack recommends no more than 200 results at a time.
            // https://api.slack.com/methods/conversations.list
            ConversationsListResponse response = slack.methods(slackEndpoint.getToken()).conversationsList(req -> req
                    .types(Collections.singletonList(slackEndpoint.getConversationType()))
                    .cursor(cursor)
                    .limit(200));

            if (!response.isOk()) {
                throw new RuntimeCamelException("API request conversations.list to Slack failed: " + response);
            }

            return response.getChannels().stream()
                    .filter(it -> it.getName().equals(channel))
                    .map(Conversation::getId)
                    .findFirst().orElseGet(() -> {
                        if (isNullOrEmpty(response.getResponseMetadata().getNextCursor())) {
                            throw new RuntimeCamelException(String.format("Channel %s not found", channel));
                        }
                        return getChannelId(channel, response.getResponseMetadata().getNextCursor());
                    });
        } catch (IOException | SlackApiException e) {
            throw new RuntimeCamelException("API request conversations.list to Slack failed", e);
        }
    }

    private static boolean isNullOrEmpty(String str) {
        return str == null || str.isEmpty();
    }

    private Exchange createExchange(Message object) {
        Exchange exchange = createExchange(true);
        exchange.getIn().setBody(object);
        return exchange;
    }
}
