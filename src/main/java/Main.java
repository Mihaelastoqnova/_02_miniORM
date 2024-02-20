import orm.MyConnector;

import java.sql.Connection;
import java.sql.SQLException;

public class Main {
    public static void main(String[] args) throws SQLException {
        MyConnector.createConnection("root", "12345", "mini_orm");
        Connection connection = MyConnector.getConnection();
    }
}
