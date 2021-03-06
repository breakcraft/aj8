package org.apollo.net.session;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apollo.fs.FileSystem;
import org.apollo.game.GameConstants;
import org.apollo.game.GameService;
import org.apollo.game.event.EventTranslator;
import org.apollo.game.model.Player;
import org.apollo.game.model.World.RegistrationStatus;
import org.apollo.io.player.PlayerSerializerResponse;
import org.apollo.io.player.PlayerSerializerWorker;
import org.apollo.net.NetworkConstants;
import org.apollo.net.codec.game.GameEventDecoder;
import org.apollo.net.codec.game.GameEventEncoder;
import org.apollo.net.codec.game.GamePacketDecoder;
import org.apollo.net.codec.game.GamePacketEncoder;
import org.apollo.net.codec.login.LoginConstants;
import org.apollo.net.codec.login.LoginRequest;
import org.apollo.net.codec.login.LoginResponse;
import org.apollo.security.IsaacRandomPair;

/**
 * A login session.
 * 
 * @author Graham
 */
public final class LoginSession extends Session {

    /**
     * The regex pattern used to determine valid credentials.
     */
    private static final Pattern PATTERN = Pattern.compile("[A-Za-z0-9\\s]{2,}");

    /**
     * The event translator.
     */
    private final EventTranslator eventTranslator;

    /**
     * The file system.
     */
    private final FileSystem fileSystem;

    /**
     * The player synchronizer.
     */
    private final PlayerSerializerWorker playerSerializer;

    /**
     * The game service.
     */
    private final GameService gameService;

    /**
     * Creates a login session for the specified channel.
     * 
     * @param ctx The channels context.
     * @param eventTranslator The event translator.
     * @param fileSystem The file system
     * @param playerSerializer The player serializer.
     * @param gameSession The game session.
     */
    public LoginSession(ChannelHandlerContext ctx, EventTranslator eventTranslator, FileSystem fileSystem, PlayerSerializerWorker playerSerializer, GameService gameService) {
	super(ctx);
	this.eventTranslator = eventTranslator;
	this.fileSystem = fileSystem;
	this.playerSerializer = playerSerializer;
	this.gameService = gameService;
    }

    @Override
    public void messageReceived(Object message) throws Exception {
	if (message.getClass() == LoginRequest.class) {
	    handleLoginRequest((LoginRequest) message);
	}
    }

    /**
     * Handles a login request.
     * 
     * @param request The login request.
     * @throws IOException If some I/O error occurs.
     */
    private void handleLoginRequest(LoginRequest request) throws IOException {
	int code = LoginConstants.STATUS_OK;

	if (requiresUpdate(request)) {
	    code = LoginConstants.STATUS_GAME_UPDATED;
	} else if (badCredentials(request)) {
	    code = LoginConstants.STATUS_INVALID_CREDENTIALS;
	}

	if (code == LoginConstants.STATUS_OK) {
	    playerSerializer.submitLoadRequest(this, request, fileSystem);
	} else {
	    handlePlayerLoaderResponse(request, new PlayerSerializerResponse(code));
	}
    }

    /**
     * Checks if an update is required whenever a {@link Player} submits a login
     * request.
     * 
     * @param request The login request.
     * @return {@code true} if an update is required, otherwise return
     *         {@code false}.
     * @throws IOException If some I/O exception occurs.
     */
    private boolean requiresUpdate(LoginRequest request) throws IOException {
	if (GameConstants.VERSION != request.getCurrentVersion()) {
	    return true;
	}

	ByteBuffer buffer = ByteBuffer.wrap(fileSystem.getArchiveHashes());

	int[] clientCrcs = request.getArchiveCrcs();
	int[] serverCrcs = new int[clientCrcs.length];

	if (buffer.remaining() < clientCrcs.length) {
	    return true;
	}

	for (int crc = 0, len = serverCrcs.length; crc < len; crc++) {
	    serverCrcs[crc] = buffer.getInt();
	}

	if (Arrays.equals(clientCrcs, serverCrcs)) {
	    return false;
	}

	return true;
    }

    /**
     * Returns {@code true} if the credentials within the specified login
     * request are invalid otherwise {@code false}.
     * 
     * @param request The login request.
     */
    private boolean badCredentials(LoginRequest request) {
	String username = request.getCredentials().getUsername();
	String password = request.getCredentials().getPassword();

	if (username.length() == 0 || password.length() == 0) {
	    return true;
	}

	if (username.length() > 12 || password.length() > 20) {
	    return true;
	}

	/* Indicates username contains more than one piece of whitespace. */
	String[] parts = username.split("\\s\\s+");
	if (parts.length > 1) {
	    return true;
	}

	Matcher usernameMatcher = PATTERN.matcher(username);
	Matcher passwordMatcher = PATTERN.matcher(password);

	if (usernameMatcher.matches() && passwordMatcher.matches()) {
	    return false;
	}

	return true;
    }

    /**
     * Handles a response from the login service.
     * 
     * @param request The request this response corresponds to.
     * @param response The response.
     */
    public void handlePlayerLoaderResponse(LoginRequest request, PlayerSerializerResponse response) {
	int status = response.getStatus();
	Player player = response.getPlayer();
	int rights = player == null ? 0 : player.getPrivilegeLevel().toInteger();
	// TODO: Utilize the logging packet! :- )
	boolean log = false;

	if (player != null) {
	    GameSession session = new GameSession(ctx(), eventTranslator, player, gameService);
	    player.setSession(session, request.isReconnecting());

	    RegistrationStatus registrationStatus = gameService.registerPlayer(player);

	    if (registrationStatus != RegistrationStatus.OK) {
		player = null;
		if (registrationStatus == RegistrationStatus.ALREADY_ONLINE) {
		    status = LoginConstants.STATUS_ACCOUNT_ONLINE;
		} else {
		    status = LoginConstants.STATUS_SERVER_FULL;
		}
		rights = 0;
	    }
	}

	Channel channel = ctx().channel();
	ChannelFuture future = channel.writeAndFlush(new LoginResponse(status, rights, log));

	if (player != null) {
	    IsaacRandomPair randomPair = request.getRandomPair();

	    channel.pipeline().addFirst("eventEncoder", new GameEventEncoder(eventTranslator));
	    channel.pipeline().addBefore("eventEncoder", "gameEncoder", new GamePacketEncoder(randomPair.getEncodingRandom()));

	    channel.pipeline().addBefore("handler", "gameDecoder", new GamePacketDecoder(randomPair.getDecodingRandom(), eventTranslator));
	    channel.pipeline().addAfter("gameDecoder", "eventDecoder", new GameEventDecoder(eventTranslator));

	    channel.pipeline().remove("loginDecoder");
	    channel.pipeline().remove("loginEncoder");

	    ctx().attr(NetworkConstants.NETWORK_SESSION).set(player.getSession());
	} else {
	    future.addListener(ChannelFutureListener.CLOSE);
	}
    }

}
