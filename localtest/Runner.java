import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;

/** Runs every method annotated with org.junit.Test in the given classes. Usage: java Runner Class1 Class2 ... */
public class Runner {
    public static void main(String[] args) throws Exception {
        int passed = 0, failed = 0;
        for (String name : args) {
            Class<?> c = Class.forName(name);
            for (Method m : c.getDeclaredMethods()) {
                if (!m.isAnnotationPresent(org.junit.Test.class)) continue;
                Object inst = c.getDeclaredConstructor().newInstance();
                long t0 = System.nanoTime();
                try {
                    m.invoke(inst);
                    passed++;
                    System.out.printf("PASS %-28s %-48s %6.0f ms%n", c.getSimpleName(), m.getName(), (System.nanoTime() - t0) / 1e6);
                } catch (InvocationTargetException e) {
                    failed++;
                    System.out.printf("FAIL %-28s %s%n", c.getSimpleName(), m.getName());
                    e.getCause().printStackTrace(System.out);
                }
            }
        }
        System.out.println("passed=" + passed + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
