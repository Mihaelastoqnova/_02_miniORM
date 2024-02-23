import entities.User;
import orm.EntityManager;
import orm.MyConnector;

import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;

public class Main {
    public static void main(String[] args) throws SQLException, IllegalAccessException, InvocationTargetException, NoSuchMethodException, InstantiationException {
        MyConnector.createConnection("root", "12345", "mini_orm");
        Connection connection = MyConnector.getConnection();

        EntityManager<User> userEntityManager = new EntityManager<>(connection);
        User pesho = new User("pesho", 43, LocalDate.now());
        pesho.setId(1);

        userEntityManager.persist(pesho);

        Iterable users = userEntityManager.find(User.class, "age > 40");

        System.out.println(users.iterator().next());

    }
}
