/* Licensed under the Apache License, Version 2.0 (the "License");
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
package org.camunda.tngp.client.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.camunda.tngp.client.cmd.CompleteTaskCmd;
import org.camunda.tngp.client.cmd.FailTaskCmd;
import org.camunda.tngp.client.event.TopicEventType;
import org.camunda.tngp.client.event.impl.EventAcquisition;
import org.camunda.tngp.client.event.impl.TopicEventImpl;
import org.camunda.tngp.client.impl.TaskTopicClientImpl;
import org.camunda.tngp.client.impl.cmd.taskqueue.CloseTaskSubscriptionCmdImpl;
import org.camunda.tngp.client.impl.cmd.taskqueue.CreateTaskSubscriptionCmdImpl;
import org.camunda.tngp.client.impl.cmd.taskqueue.IncreaseTaskSubscriptionCreditsCmdImpl;
import org.camunda.tngp.client.impl.cmd.taskqueue.TaskEventType;
import org.camunda.tngp.client.impl.data.MsgPackConverter;
import org.camunda.tngp.client.impl.data.MsgPackMapper;
import org.camunda.tngp.client.task.impl.EventSubscriptionCreationResult;
import org.camunda.tngp.client.task.impl.EventSubscriptions;
import org.camunda.tngp.client.task.impl.SubscribedEventCollector;
import org.camunda.tngp.client.task.impl.TaskSubscriptionImpl;
import org.camunda.tngp.test.util.FluentMock;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.msgpack.jackson.dataformat.MessagePackFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

public class TaskSubscriptionUnitTest
{

    public static final int TOPIC_ID = 0;
    public static final long SUBSCRIPTION_ID = 123L;
    private static final String TASK_TYPE = "foo";
    private static final int LOCK_OWNER = 1;
    private static final long LOCK_TIME = 123L;

    protected final ObjectMapper objectMapper = new ObjectMapper(new MessagePackFactory());
    private final MsgPackConverter msgPackConverter = new MsgPackConverter();
    protected final MsgPackMapper msgPackMapper = new MsgPackMapper(objectMapper);

    protected EventSubscriptions<TaskSubscriptionImpl> subscriptions;
    protected EventAcquisition<TaskSubscriptionImpl> acquisition;

    @Mock
    protected TaskTopicClientImpl client;

    @FluentMock
    protected CreateTaskSubscriptionCmdImpl createSubscriptionCmd;

    @FluentMock
    protected CloseTaskSubscriptionCmdImpl closeSubscriptionCmd;

    @FluentMock
    protected IncreaseTaskSubscriptionCreditsCmdImpl updateCreditsCmd;

    @FluentMock
    protected CompleteTaskCmd completeCmd;

    @FluentMock
    protected FailTaskCmd failCmd;

    @Mock
    protected TaskHandler taskHandler;

    @Mock
    protected SubscribedEventCollector taskCollector;

    @Rule
    public ExpectedException exception = ExpectedException.none();

    @Before
    public void setUp()
    {
        MockitoAnnotations.initMocks(this);
        when(client.brokerTaskSubscription()).thenReturn(createSubscriptionCmd);
        when(createSubscriptionCmd.execute()).thenReturn(new EventSubscriptionCreationResult(SUBSCRIPTION_ID, 5));
        when(client.closeBrokerTaskSubscription()).thenReturn(closeSubscriptionCmd);
        when(client.increaseSubscriptionCredits()).thenReturn(updateCreditsCmd);
        when(client.complete()).thenReturn(completeCmd);
        when(client.fail()).thenReturn(failCmd);

        subscriptions = new EventSubscriptions<>();
        acquisition = new EventAcquisition<>(subscriptions);
    }

    @Test
    public void shouldOpenManagedExecutionSubscription() throws Exception
    {
        // given
        final TaskSubscriptionImpl subscription = newDefaultSubscription();

        // when
        subscription.openAsync();
        final int workCount = acquisition.doWork();

        // then
        assertThat(workCount).isEqualTo(1);

        assertThat(subscription.isOpen()).isTrue();
        assertThat(subscriptions.getManagedSubscriptions()).containsExactly(subscription);
        assertThat(subscriptions.getPollableSubscriptions()).isEmpty();

        verify(client).brokerTaskSubscription();
        verify(createSubscriptionCmd).taskType(TASK_TYPE);
        verify(createSubscriptionCmd).lockDuration(LOCK_TIME);
        verify(createSubscriptionCmd).lockOwner(LOCK_OWNER);
        verify(createSubscriptionCmd).initialCredits(anyInt());
        verify(createSubscriptionCmd).execute();
    }

    @Test
    public void shouldCloseManagedExecutionSubscription() throws Exception
    {
        // given
        final TaskSubscriptionImpl subscription = newDefaultSubscription();

        subscription.openAsync();
        acquisition.doWork();

        // when
        subscription.closeAsync();

        // then the subscription is staged as closing
        assertThat(subscription.isOpen()).isFalse();
        assertThat(subscription.isClosing()).isTrue();
        assertThat(subscription.isClosed()).isFalse();
        assertThat(subscriptions.getManagedSubscriptions()).isNotEmpty();

        // and closed on the next acquisition cycle
        final int workCount = acquisition.manageSubscriptions();

        assertThat(workCount).isEqualTo(1);

        assertThat(subscription.isOpen()).isFalse();
        assertThat(subscription.isClosing()).isFalse();
        assertThat(subscription.isClosed()).isTrue();
        assertThat(subscriptions.getManagedSubscriptions()).isEmpty();

        verify(client).closeBrokerTaskSubscription();
        verify(closeSubscriptionCmd).subscriptionId(SUBSCRIPTION_ID);
        verify(closeSubscriptionCmd).execute();
    }

    @Test
    public void shouldInvokeHandlerOnPoll() throws Exception
    {
        // given
        final TaskSubscriptionImpl subscription = newDefaultSubscription();

        subscription.openAsync();
        acquisition.doWork();

        // two subscribed tasks
        acquisition.onEvent(TOPIC_ID, SUBSCRIPTION_ID, task(1L, 1L));
        acquisition.onEvent(TOPIC_ID, SUBSCRIPTION_ID, task(2L, 2L));

        // when
        final int workCount = subscription.poll();

        // then
        assertThat(workCount).isEqualTo(2);

        verify(taskHandler).handle(argThat(hasKey(1)));
        verify(taskHandler).handle(argThat(hasKey(2)));
        verifyNoMoreInteractions(taskHandler);
    }

    @Test
    public void shouldAutoCompleteTask() throws Exception
    {
        // given
        final TaskSubscriptionImpl subscription = newDefaultSubscription();

        subscription.openAsync();
        acquisition.doWork();

        final TopicEventImpl event = task(1L, 1L);
        acquisition.onEvent(TOPIC_ID, SUBSCRIPTION_ID, event);

        // when
        subscription.poll();

        // then
        verify(client).complete();
        verify(completeCmd).taskKey(1L);
        verify(completeCmd).taskType(TASK_TYPE);
        verify(completeCmd).lockOwner(LOCK_OWNER);
        verify(completeCmd).headers(eq(new HashMap<>()));
        verify(completeCmd).payload("{}");
        verify(completeCmd).execute();
    }

    @Test
    public void shouldNotAutoCompleteTask() throws Exception
    {
        // given
        final TaskSubscriptionImpl subscription = new TaskSubscriptionImpl(
                client, taskHandler, TASK_TYPE, LOCK_TIME, LOCK_OWNER, 5, acquisition, msgPackMapper, false);

        subscription.openAsync();
        acquisition.doWork();

        acquisition.onEvent(TOPIC_ID, SUBSCRIPTION_ID, task(1L, 1L));

        // when
        subscription.poll();

        // then
        verify(client, never()).complete();
        verifyZeroInteractions(completeCmd);
    }

    @Test
    public void shouldMarkTaskAsFailedOnException() throws Exception
    {
        // given
        doThrow(new RuntimeException()).when(taskHandler).handle(any());

        final TaskSubscriptionImpl subscription = newDefaultSubscription();

        subscription.openAsync();
        acquisition.doWork();

        final TopicEventImpl event = task(1L, 1L);

        acquisition.onEvent(TOPIC_ID, SUBSCRIPTION_ID, event);

        // when
        try
        {
            subscription.poll();
        }
        catch (Exception e)
        {
           // expected
        }

        // then
        verify(client).fail();
        verify(failCmd).taskKey(1L);
        verify(failCmd).taskType(TASK_TYPE);
        verify(failCmd).lockOwner(LOCK_OWNER);
        verify(failCmd).headers(eq(new HashMap<>()));
        verify(failCmd).payload("{}");
        verify(failCmd).failure(any(RuntimeException.class));
        verify(failCmd).execute();

        verify(client, never()).complete();
        verify(completeCmd, never()).execute();
    }

    @Test
    public void shouldDistributeTasks() throws Exception
    {
        // given
        final TaskSubscriptionImpl subscription = newDefaultSubscription();

        subscription.openAsync();
        acquisition.doWork();

        // when
        acquisition.onEvent(TOPIC_ID, SUBSCRIPTION_ID, task(1L, 1L));
        acquisition.onEvent(TOPIC_ID, SUBSCRIPTION_ID, task(2L, 1L));
        acquisition.onEvent(TOPIC_ID, SUBSCRIPTION_ID, task(3L, 1L));

        // then
        assertThat(subscription.size()).isEqualTo(3);
    }

    @Test
    public void shouldDistributeWithTwoSubscriptionsForSameType() throws Exception
    {
        // given
        final TaskSubscriptionImpl subscription1 = newDefaultSubscription();
        final TaskSubscriptionImpl subscription2 = newDefaultSubscription();

        subscription1.openAsync();
        subscription2.openAsync();
        acquisition.doWork();

        // when
        acquisition.onEvent(TOPIC_ID, SUBSCRIPTION_ID, task(1L, 1L));

        // then
        assertThat(subscription1.size() + subscription2.size()).isEqualTo(1);
    }

    @Test
    public void shouldNotDistributeMoreThanSubscriptionCapacity() throws Exception
    {
        // given
        final TaskSubscriptionImpl subscription = newDefaultSubscription();

        subscription.openAsync();
        acquisition.doWork();

        for (int i = 0; i < subscription.capacity(); i++)
        {
            subscription.addEvent(mock(TopicEventImpl.class));
        }

        // then
        exception.expect(RuntimeException.class);
        exception.expectMessage("Cannot add any more events. Event queue saturated.");

        // when
        acquisition.onEvent(TOPIC_ID, SUBSCRIPTION_ID, task(1L, 1L));
    }

    @Test
    public void shouldOpenPollableSubscription() throws Exception
    {
        // given
        final TaskSubscriptionImpl subscription = newDefaultSubscription(null);

        // when
        subscription.openAsync();
        final int workCount = acquisition.doWork();

        // then
        assertThat(workCount).isEqualTo(1);
        assertThat(subscription.isOpen()).isTrue();
        assertThat(subscriptions.getPollableSubscriptions()).containsExactly(subscription);
        assertThat(subscriptions.getManagedSubscriptions()).isEmpty();
    }

    @Test
    public void shouldPollSubscription() throws Exception
    {
        // given
        final TaskSubscriptionImpl subscription = newDefaultSubscription();

        subscription.openAsync();
        acquisition.doWork();

        acquisition.onEvent(TOPIC_ID, SUBSCRIPTION_ID, task(1L, 1L));
        acquisition.onEvent(TOPIC_ID, SUBSCRIPTION_ID, task(2L, 2L));

        // when
        int workCount = subscription.poll(taskHandler);

        // then
        assertThat(workCount).isEqualTo(2);

        verify(taskHandler).handle(argThat(hasKey(1)));
        verify(taskHandler).handle(argThat(hasKey(2)));

        // and polling again does not trigger the handler anymore
        workCount = subscription.poll(taskHandler);
        assertThat(workCount).isEqualTo(0);

        verifyNoMoreInteractions(taskHandler);
    }

    @Test
    public void shouldIncreaseCredits() throws Exception
    {
        // given
        final TaskSubscriptionImpl subscription = newDefaultSubscription();

        subscription.openAsync();
        acquisition.doWork();

        acquisition.onEvent(TOPIC_ID, SUBSCRIPTION_ID, task(1L, 1L));
        acquisition.onEvent(TOPIC_ID, SUBSCRIPTION_ID, task(2L, 1L));
        acquisition.onEvent(TOPIC_ID, SUBSCRIPTION_ID, task(3L, 1L));
        acquisition.onEvent(TOPIC_ID, SUBSCRIPTION_ID, task(4L, 1L));

        // when
        subscription.poll(taskHandler);
        acquisition.doWork();

        // then
        verify(client, times(1)).increaseSubscriptionCredits();
        verify(updateCreditsCmd).subscriptionId(subscription.getId());
        verify(updateCreditsCmd).credits(4);
        verify(updateCreditsCmd, times(1)).execute();
    }

    @Test
    public void shouldPopulateTaskProperties() throws Exception
    {
        // given
        final TaskSubscriptionImpl subscription = newDefaultSubscription();

        subscription.openAsync();
        acquisition.doWork();

        acquisition.onEvent(TOPIC_ID, SUBSCRIPTION_ID, task(1L, 1L));

        // when
        subscription.poll(taskHandler);

        // then
        verify(taskHandler).handle(argThat(new ArgumentMatcher<Task>()
        {
            @Override
            public boolean matches(Object argument)
            {
                final Task task = (Task) argument;
                return task.getKey() == 1L && TASK_TYPE.equals(task.getType());
            }
        }));
    }

    protected TopicEventImpl task(long position, long key)
    {
        final Map<String, Object> taskEvent = new HashMap<>();
        taskEvent.put("eventType", TaskEventType.LOCKED.toString());
        taskEvent.put("lockTime", LOCK_TIME);
        taskEvent.put("lockOwner", LOCK_OWNER);
        taskEvent.put("retries", 3);
        taskEvent.put("payload", msgPackConverter.convertToMsgPack("{}"));
        taskEvent.put("headers", new HashMap<>());
        taskEvent.put("type", TASK_TYPE);

        final byte[] encodedEvent;
        try
        {
            encodedEvent = objectMapper.writeValueAsBytes(taskEvent);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }

        return new TopicEventImpl(TOPIC_ID, key, position, TopicEventType.TASK, encodedEvent);
    }

    protected static ArgumentMatcher<Task> hasKey(final long taskKey)
    {
        return new ArgumentMatcher<Task>()
        {
            @Override
            public boolean matches(Object argument)
            {
                return argument instanceof Task && ((Task) argument).getKey() == taskKey;
            }
        };
    }

    protected TaskSubscriptionImpl newDefaultSubscription()
    {
        return newDefaultSubscription(taskHandler);
    }

    protected TaskSubscriptionImpl newDefaultSubscription(TaskHandler taskHandler)
    {
        return new TaskSubscriptionImpl(
                client, taskHandler, TASK_TYPE, LOCK_TIME, LOCK_OWNER, 5, acquisition, msgPackMapper, true);
    }

}
