package com.polozov.nio;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public class NioTelnetServer {
	public static final String LS_COMMAND = "ls    view all files and directories" + System.lineSeparator();
	public static final String MKDIR_COMMAND = "mkdir    create directory" + System.lineSeparator();
	public static final String CHANGE_NICKNAME = "nick    change nickname" + System.lineSeparator();
	private static final String ROOT_NOTIFICATION = "You are already in the root directory";
	private static final String DIRECTORY_DOESNOT_EXIST = "Directory $s doesn't exist" + System.lineSeparator();
	private static final String ROOT_PATH = "server";

	private Path currentPath = Path.of("server");

	private final ByteBuffer buffer = ByteBuffer.allocate(512);

	private Map<SocketAddress, String> clients = new HashMap<>();

	public NioTelnetServer() throws IOException {
		ServerSocketChannel server = ServerSocketChannel.open();
		server.bind(new InetSocketAddress(5678));
		server.configureBlocking(false);
		// OP_ACCEPT, OP_READ, OP_WRITE
		Selector selector = Selector.open();

		server.register(selector, SelectionKey.OP_ACCEPT);
		System.out.println("Server started");

		while (server.isOpen()) {
			selector.select();

			var selectionKeys = selector.selectedKeys();
			var iterator = selectionKeys.iterator();

			while (iterator.hasNext()) {
				var key = iterator.next();
				if (key.isAcceptable()) {
					handleAccept(key, selector);
				} else if (key.isReadable()) {
					handleRead(key, selector);
				}
				iterator.remove();
			}
		}
	}

	private void handleRead(SelectionKey key, Selector selector) throws IOException {
		SocketChannel channel = ((SocketChannel) key.channel());
		SocketAddress client = channel.getRemoteAddress();

		String nickname = "";

		int readBytes = channel.read(buffer);
		if (readBytes < 0) {
			channel.close();
			return;
		} else if (readBytes == 0) {
			return;
		}

		buffer.flip();

		StringBuilder sb = new StringBuilder();
		while (buffer.hasRemaining()) {
			sb.append((char) buffer.get());
		}

		buffer.clear();

		// TODO
		// touch [filename] - создание файла
		// mkdir [dirname] - создание директории
		// cd [path] - перемещение по каталогу (.. | ~ )
		// rm [filename | dirname] - удаление файла или папки
		// copy [src] [target] - копирование файла или папки
		// cat [filename] - просмотр содержимого
		// вывод nickname в начале строки

		// NIO
		// NIO telnet server

		if (key.isValid()) {
			String command = sb
					.toString()
					.replace("\n", "")
					.replace("\r", "");

			if ("--help".equals(command)) {
				sendMessage(LS_COMMAND, selector, client);
				sendMessage(MKDIR_COMMAND, selector, client);
				sendMessage(CHANGE_NICKNAME, selector, client);
			} else if ("ls".equals(command)) {
				sendMessage(getFileList().concat(System.lineSeparator()), selector, client);
			} else if (command.startsWith("nick")) {
				nickname = command.split(" ")[1];
				clients.put(channel.getRemoteAddress(), nickname);
				sendMessage(getFileList().concat(System.lineSeparator()), selector, client);
				System.out.println(
						"Client " + channel.getRemoteAddress().toString() + "changed nickname on " + nickname
				);

				System.out.println(clients);

			} else if (command.startsWith("cd ")) {

				replacePosition(selector, client, command);

			}
			else if (command.startsWith("cat")) {
				String filename = command.split(" ")[1];

				Path server = Path.of("server", filename);

				Files.newBufferedReader(server).lines().forEach(System.out::println);
//				System.out.println("-----------------");

			} else if (command.startsWith("touch")) {
				String filename = command.split(" ")[1];
				Path path = Path.of("server/", filename);
				if (!Files.exists(path)) {
					Files.createFile(path);
					channel.write(ByteBuffer.wrap(("File created:" + filename + System.lineSeparator()).getBytes(StandardCharsets.UTF_8)));
				}
			}  else if (command.startsWith("mkdir")) {
				String dirname = command.split(" ")[1];
				Path path = Path.of("server/", dirname);
				if (!Files.exists(path)) {
					Files.createDirectory(path);
					channel.write(ByteBuffer.wrap(("Directory created:" + dirname + System.lineSeparator()).getBytes(StandardCharsets.UTF_8)));
				}
			}  else if (command.startsWith("rm")) {
				String name = command.split(" ")[1];
				Path path = Path.of("server/", name);
				if (Files.exists(path)) {
					Files.deleteIfExists(path);
					channel.write(ByteBuffer.wrap(("file | directory deleted:" + name + System.lineSeparator()).getBytes(StandardCharsets.UTF_8)));
				}
			}  else if (command.startsWith("copy")) {
				String source = command.split(" ")[1];
				String target = command.split(" ")[2];
				Path sourcePath = Path.of("server/", source);
				Path targetPath = Path.of("", target);
				if (Files.exists(sourcePath)) {
					Files.copy(sourcePath, targetPath, REPLACE_EXISTING);
					channel.write(ByteBuffer.wrap(("file | directory moved to:" + target + System.lineSeparator()).getBytes(StandardCharsets.UTF_8)));
				}
			} else if ("exit".equals(command)) {
				System.out.println("Client logged out. IP: " + channel.getRemoteAddress());
				channel.close();
				return;
			}
		}
		sendName(channel, nickname);
	}

	private void replacePosition(Selector selector, SocketAddress client, String command) throws IOException {
		String neededPathString = command.split(" ")[1];
		Path tempPath = Path.of(currentPath.toString(), neededPathString);

		if (".. ".equals(neededPathString)) {
			tempPath = currentPath.getParent();
			if (tempPath == null || !tempPath.toString().startsWith("server")){
				sendMessage(ROOT_NOTIFICATION, selector, client);
			} else {
				currentPath = tempPath;
			}
		} else if ("~". equals(neededPathString)) {
			currentPath = Path.of(ROOT_PATH);
		} else {
			if (tempPath.toFile().exists()) {
				currentPath = tempPath;
			} else {
				sendMessage((String.format(DIRECTORY_DOESNOT_EXIST, neededPathString)), selector, client);
			}
		}

	}

	private void sendName(SocketChannel channel, String nickname) throws IOException {
		if (nickname.isEmpty()) {
			nickname = clients.getOrDefault(channel.getRemoteAddress(), channel.getRemoteAddress().toString());
		}
		String currentPathString = currentPath.toString().replace("server", "~");

		channel.write(
				ByteBuffer.wrap(nickname.concat(">:").concat(currentPathString).concat("$")
						.getBytes(StandardCharsets.UTF_8)
				));
	}

	private String getFileList() {
		return String.join(" ", new File("server").list());
	}

	private void sendMessage(String message, Selector selector, SocketAddress client) throws IOException {
		for (SelectionKey key : selector.keys()) {
			if (key.isValid() && key.channel() instanceof SocketChannel) {
				if (((SocketChannel)key.channel()).getRemoteAddress().equals(client)) {
					((SocketChannel)key.channel())
							.write(ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8)));
				}
			}
		}
	}

	private void handleAccept(SelectionKey key, Selector selector) throws IOException {
		SocketChannel channel = ((ServerSocketChannel) key.channel()).accept();
		channel.configureBlocking(false);
		System.out.println("Client accepted. IP: " + channel.getRemoteAddress());

		channel.register(selector, SelectionKey.OP_READ, "some attach");
		channel.write(ByteBuffer.wrap("Hello user!\r\n".getBytes(StandardCharsets.UTF_8)));
		channel.write(ByteBuffer.wrap("Enter --help for support info\r\n".getBytes(StandardCharsets.UTF_8)));
		sendName(channel, "");
	}

	public static void main(String[] args) throws IOException {
		new NioTelnetServer();
	}
}
