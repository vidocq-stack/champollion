package io.vidocq.champollion.jsonb.internal;

import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.annotation.JsonbNillable;
import jakarta.json.bind.annotation.JsonbProperty;
import jakarta.json.bind.annotation.JsonbTransient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("M4.4 — JSON-B customization (runtime)")
class JsonbCustomizationTest {

    @Nested
    @DisplayName("@JsonbProperty(name)")
    class PropertyRenaming {

        record Snake(@JsonbProperty("user_name") String userName, @JsonbProperty("age_years") int age) {}

        @Test
        void writes_renamed_members() {
            try (var j = JsonbBuilder.create()) {
                String json = j.toJson(new Snake("alice", 30));
                assertEquals("{\"user_name\":\"alice\",\"age_years\":30}", json);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void reads_renamed_members() {
            try (var j = JsonbBuilder.create()) {
                Snake s = j.fromJson("{\"user_name\":\"bob\",\"age_years\":25}", Snake.class);
                assertEquals(new Snake("bob", 25), s);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void original_name_is_ignored_when_property_renamed() {
            // userName ne doit PAS apparaître dans le JSON ; seulement user_name
            try (var j = JsonbBuilder.create()) {
                Snake s = j.fromJson("{\"userName\":\"bob\",\"user_name\":\"alice\",\"age_years\":30}", Snake.class);
                assertEquals(new Snake("alice", 30), s,
                        "Reader doit utiliser le nom annoté, pas le nom du composant.");
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    @Nested
    @DisplayName("@JsonbTransient — exclusion")
    class TransientExclusion {

        public static final class Account {
            public String username;
            @JsonbTransient public String password;

            public Account() {}
            public Account(String u, String p) { this.username = u; this.password = p; }
        }

        @Test
        void transient_field_excluded_from_write() {
            try (var j = JsonbBuilder.create()) {
                String json = j.toJson(new Account("alice", "s3cret"));
                // password absent
                assertEquals("{\"username\":\"alice\"}", json);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void transient_field_ignored_on_read() {
            try (var j = JsonbBuilder.create()) {
                Account a = j.fromJson("{\"username\":\"bob\",\"password\":\"s3cret\"}", Account.class);
                assertEquals("bob", a.username);
                assertNull(a.password, "Champ @JsonbTransient ne doit pas être affecté par la lecture.");
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    @Nested
    @DisplayName("@JsonbNillable — force-include null members")
    class NillableInclusion {

        record Doc(String title, @JsonbNillable String description) {}

        @Test
        void nillable_member_serialized_as_null_when_null() {
            try (var j = JsonbBuilder.create()) {
                String json = j.toJson(new Doc("Champollion", null));
                // description doit apparaître avec null, pas être omise.
                assertEquals("{\"title\":\"Champollion\",\"description\":null}", json);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        void non_nillable_null_remains_omitted() {
            // title sans @JsonbNillable, son null reste omis.
            record Plain(String title, String desc) {}
            try (var j = JsonbBuilder.create()) {
                String json = j.toJson(new Plain(null, null));
                assertEquals("{}", json);
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    @Nested
    @DisplayName("Mixed @JsonbProperty + @JsonbTransient on records")
    class Mixed {

        record User(
                @JsonbProperty("user_id") String id,
                String name,
                @JsonbTransient String secret) {}

        @Test
        void rename_some_exclude_others() {
            try (var j = JsonbBuilder.create()) {
                String json = j.toJson(new User("u1", "Alice", "hidden"));
                // id renommé, name normal, secret absent
                assertEquals("{\"user_id\":\"u1\",\"name\":\"Alice\"}", json);

                User back = j.fromJson("{\"user_id\":\"u2\",\"name\":\"Bob\",\"secret\":\"ignored\"}", User.class);
                assertEquals("u2", back.id());
                assertEquals("Bob", back.name());
                assertNull(back.secret(), "secret doit rester null après lecture, malgré la valeur dans le JSON.");
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }
}
