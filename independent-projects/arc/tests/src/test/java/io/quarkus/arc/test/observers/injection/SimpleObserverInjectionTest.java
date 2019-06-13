package io.quarkus.arc.test.observers.injection;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.quarkus.arc.Arc;
import io.quarkus.arc.test.ArcTestContainer;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.Dependent;
import javax.enterprise.event.Observes;
import javax.inject.Singleton;
import org.junit.Rule;
import org.junit.Test;

public class SimpleObserverInjectionTest {

    @Rule
    public ArcTestContainer container = new ArcTestContainer(Fool.class, StringObserver.class);

    @Test
    public void testObserverInjection() {
        AtomicReference<String> msg = new AtomicReference<String>();
        Fool.DESTROYED.set(false);
        Arc.container().beanManager().fireEvent(msg);
        String id1 = msg.get();
        assertNotNull(id1);
        assertTrue(Fool.DESTROYED.get());
        Fool.DESTROYED.set(false);
        Arc.container().beanManager().fireEvent(msg);
        assertNotEquals(id1, msg.get());
        assertTrue(Fool.DESTROYED.get());
    }

    @Singleton
    static class StringObserver {

        @SuppressWarnings({ "rawtypes", "unchecked" })
        void observeString(@Observes AtomicReference value, Fool fool) {
            value.set(fool.id);
        }

    }

    @Dependent
    static class Fool {

        static final AtomicBoolean DESTROYED = new AtomicBoolean();

        private String id;

        @PostConstruct
        void init() {
            id = UUID.randomUUID().toString();
        }

        @PreDestroy
        void destroy() {
            DESTROYED.set(true);
        }
    }

}
