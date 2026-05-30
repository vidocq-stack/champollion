package io.vidocq.champollion.bench;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Workloads partagés pour les benchmarks Champollion vs Yasson/Parsson/Jackson.
 *
 * <p>Trois tailles : SMALL (~50 B), MEDIUM (~1 KB), LARGE (~50 KB / 1000 items).</p>
 */
public final class Workloads {

    private Workloads() {}

    /** Petit POJO pour benchmarks rapides. */
    public static final class SmallPojo {
        public long id;
        public String name;
        public boolean active;
        public SmallPojo() {}
        public SmallPojo(long id, String name, boolean active) {
            this.id = id; this.name = name; this.active = active;
        }
        public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
    }

    /** Record équivalent SMALL — utilisé pour mesurer le coût records (Yasson cassait). */
    public record SmallRecord(long id, String name, boolean active) {}

    /** Item interne d'un Order. */
    public static final class Item {
        public String sku;
        public int quantity;
        public double unitPrice;
        public Item() {}
        public Item(String sku, int quantity, double unitPrice) {
            this.sku = sku; this.quantity = quantity; this.unitPrice = unitPrice;
        }
        public String getSku() { return sku; }
        public void setSku(String sku) { this.sku = sku; }
        public int getQuantity() { return quantity; }
        public void setQuantity(int quantity) { this.quantity = quantity; }
        public double getUnitPrice() { return unitPrice; }
        public void setUnitPrice(double unitPrice) { this.unitPrice = unitPrice; }
    }

    public static final class Address {
        public String street;
        public String city;
        public String zip;
        public String country;
        public Address() {}
        public Address(String street, String city, String zip, String country) {
            this.street = street; this.city = city; this.zip = zip; this.country = country;
        }
        public String getStreet() { return street; }
        public void setStreet(String street) { this.street = street; }
        public String getCity() { return city; }
        public void setCity(String city) { this.city = city; }
        public String getZip() { return zip; }
        public void setZip(String zip) { this.zip = zip; }
        public String getCountry() { return country; }
        public void setCountry(String country) { this.country = country; }
    }

    /** Ordre commercial avec quelques items et adresse. */
    public static final class Order {
        public long id;
        public String customer;
        public List<Item> items;
        public Address shipping;
        public double total;
        public boolean priority;
        public Order() {}
        public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public String getCustomer() { return customer; }
        public void setCustomer(String customer) { this.customer = customer; }
        public List<Item> getItems() { return items; }
        public void setItems(List<Item> items) { this.items = items; }
        public Address getShipping() { return shipping; }
        public void setShipping(Address shipping) { this.shipping = shipping; }
        public double getTotal() { return total; }
        public void setTotal(double total) { this.total = total; }
        public boolean isPriority() { return priority; }
        public void setPriority(boolean priority) { this.priority = priority; }
    }

    public static SmallPojo smallPojo() {
        return new SmallPojo(42L, "Champollion", true);
    }

    public static SmallRecord smallRecord() {
        return new SmallRecord(42L, "Champollion", true);
    }

    public static Order mediumOrder() {
        Order o = new Order();
        o.id = 1001L;
        o.customer = "Acme Corp";
        o.items = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            o.items.add(new Item("SKU-" + i, i + 1, 10.0 + i * 1.5));
        }
        o.shipping = new Address("12 Rue de Rivoli", "Paris", "75001", "FR");
        o.total = 87.50;
        o.priority = true;
        return o;
    }

    public static List<Order> largeBatch(int count) {
        List<Order> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Order o = mediumOrder();
            o.id = 1000L + i;
            out.add(o);
        }
        return out;
    }

    /** Serialized JSON for pull parser benchmarks (without binding). */
    public static String smallJson() {
        return "{\"id\":42,\"name\":\"Champollion\",\"active\":true}";
    }

    public static String mediumJson() {
        return "{"
                + "\"id\":1001,"
                + "\"customer\":\"Acme Corp\","
                + "\"items\":["
                +   "{\"sku\":\"SKU-0\",\"quantity\":1,\"unitPrice\":10.0},"
                +   "{\"sku\":\"SKU-1\",\"quantity\":2,\"unitPrice\":11.5},"
                +   "{\"sku\":\"SKU-2\",\"quantity\":3,\"unitPrice\":13.0},"
                +   "{\"sku\":\"SKU-3\",\"quantity\":4,\"unitPrice\":14.5},"
                +   "{\"sku\":\"SKU-4\",\"quantity\":5,\"unitPrice\":16.0}"
                + "],"
                + "\"shipping\":{\"street\":\"12 Rue de Rivoli\",\"city\":\"Paris\",\"zip\":\"75001\",\"country\":\"FR\"},"
                + "\"total\":87.5,"
                + "\"priority\":true"
                + "}";
    }

    public static String largeJson(int count) {
        StringBuilder sb = new StringBuilder(count * 200);
        sb.append('[');
        String medium = mediumJson();
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(',');
            sb.append(medium);
        }
        sb.append(']');
        return sb.toString();
    }

    /** Map non-typée pour benchmarks JsonObject builders (Champollion vs Parsson). */
    public static Map<String, Object> smallMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", 42L);
        m.put("name", "Champollion");
        m.put("active", true);
        return m;
    }
}
