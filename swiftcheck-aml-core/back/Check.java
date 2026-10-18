import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;

public class Check {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:mysql://127.0.0.1:3306/swift-check?useSSL=false&serverTimezone=UTC";
        String user = "adam";
        String password = "adam";
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            ResultSet rs = conn.createStatement().executeQuery("SHOW CREATE TABLE parser_execution");
            if (rs.next()) {
                System.out.println(rs.getString(2));
            }
        }
    }
}
