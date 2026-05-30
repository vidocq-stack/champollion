package io.vidocq.champollion.jsonb.internal;

import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.annotation.JsonbProperty;
import jakarta.json.bind.annotation.JsonbTransient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("M4.6 — POJO JavaBean conventions (getters/setters)")
class JsonbPojoBeanTest {

    /** POJO classique avec champs privés + getters/setters publics. */
    public static class Person {
        private String name;
        private int age;
        private boolean active;

        public Person() {}
        public Person(String name, int age, boolean active) {
            this.name = name; this.age = age; this.active = active;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getAge() { return age; }
        public void setAge(int age) { this.age = age; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
    }

    @Nested
    @DisplayName("Default bean discovery")
    class DefaultDiscovery {

        @Test
        void writes_via_getters() {
            try (var j = JsonbBuilder.create()) {
                String json = j.toJson(new Person("Alice", 30, true));
                // Property order not guaranteed by spec; we verify the content.
                assertTrue(json.contains("\"name\":\"Alice\""));
                assertTrue(json.contains("\"age\":30"));
                assertTrue(json.contains("\"active\":true"));
                assertTrue(json.startsWith("{") && json.endsWith("}"));
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void reads_via_setters() {
            try (var j = JsonbBuilder.create()) {
                Person p = j.fromJson("{\"name\":\"Bob\",\"age\":25,\"active\":false}", Person.class);
                assertEquals("Bob", p.getName());
                assertEquals(25, p.getAge());
                assertEquals(false, p.isActive());
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void roundtrip_preserves_state() {
            try (var j = JsonbBuilder.create()) {
                Person original = new Person("Charlie", 42, true);
                String json = j.toJson(original);
                Person back = j.fromJson(json, Person.class);
                assertEquals(original.getName(), back.getName());
                assertEquals(original.getAge(), back.getAge());
                assertEquals(original.isActive(), back.isActive());
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    /** POJO avec annotations @JsonbProperty/@JsonbTransient sur les getters. */
    public static class User {
        private String username;
        private String password;
        private int loginCount;

        public User() {}
        public User(String u, String p, int n) { username = u; password = p; loginCount = n; }

        @JsonbProperty("user_name") public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }

        @JsonbTransient public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }

        @JsonbProperty("logins") public int getLoginCount() { return loginCount; }
        public void setLoginCount(int loginCount) { this.loginCount = loginCount; }
    }

    @Nested
    @DisplayName("Annotations on getters")
    class AnnotationsOnAccessors {

        @Test
        void rename_via_jsonb_property_on_getter() {
            try (var j = JsonbBuilder.create()) {
                String json = j.toJson(new User("alice", "s3cret", 7));
                assertTrue(json.contains("\"user_name\":\"alice\""));
                assertTrue(json.contains("\"logins\":7"));
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void exclude_via_jsonb_transient_on_getter() {
            try (var j = JsonbBuilder.create()) {
                String json = j.toJson(new User("alice", "s3cret", 7));
                assertTrue(!json.contains("password"), "password annoté @JsonbTransient ne doit PAS apparaître");
                assertTrue(!json.contains("s3cret"), "valeur du password ne doit PAS apparaître");
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void reads_renamed_back() {
            try (var j = JsonbBuilder.create()) {
                User u = j.fromJson("{\"user_name\":\"bob\",\"logins\":42}", User.class);
                assertEquals("bob", u.getUsername());
                assertEquals(42, u.getLoginCount());
                assertNull(u.getPassword(), "password n'est pas désérialisé même s'il est dans le JSON");
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }
}
