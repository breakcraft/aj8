
package org.apollo.login;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apollo.Service;
import org.apollo.fs.IndexedFileSystem;
import org.apollo.game.GameConstants;
import org.apollo.game.model.Player;
import org.apollo.io.player.PlayerLoader;
import org.apollo.io.player.PlayerLoaderResponse;
import org.apollo.io.player.PlayerSaver;
import org.apollo.net.codec.login.LoginConstants;
import org.apollo.net.codec.login.LoginRequest;
import org.apollo.net.session.GameSession;
import org.apollo.net.session.LoginSession;
import org.apollo.util.NamedThreadFactory;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.JsonToken;

/**
 * The {@link LoginService} manages {@link LoginRequest}s.
 * @author Graham
 */
public final class LoginService extends Service
{

	/**
	 * The {@link ExecutorService} to which workers are submitted.
	 */
	private final ExecutorService executor = Executors.newCachedThreadPool( new NamedThreadFactory( "LoginService" ) );

	/**
	 * The current {@link PlayerLoader}.
	 */
	private PlayerLoader loader;

	/**
	 * The current {@link PlayerSaver}.
	 */
	private PlayerSaver saver;


	/**
	 * Creates the login service.
	 * @throws IOException If some I/O exceptions occurs.
	 * @throws ClassNotFoundException If the specified class is not found.
	 * @throws IllegalAccessException If we cannot access the specified class.
	 * @throws InstantiationException If some instantiation error occurs.
	 */
	public LoginService() throws IOException, ClassNotFoundException, InstantiationException, IllegalAccessException
	{
		init();
	}


	/**
	 * Initializes the login service.
	 * @throws IOException If some I/O exceptions occurs.
	 * @throws ClassNotFoundException If the specified class is not found.
	 * @throws IllegalAccessException If we cannot access the specified class.
	 * @throws InstantiationException If some instantiation error occurs.
	 */
	private void init() throws IOException, ClassNotFoundException, InstantiationException, IllegalAccessException
	{
		File file = new File( "data/", "login.json" );
		JsonFactory factory = new JsonFactory();
		JsonParser parser = factory.createJsonParser( file );

		if( parser.nextToken() != JsonToken.START_OBJECT ) {
			throw new IOException();
		}

		while( parser.nextToken() != JsonToken.END_OBJECT ) {
			String name = parser.getCurrentName();

			switch( name ) {
				case "loader":
					if( parser.nextToken() != JsonToken.START_ARRAY ) {
						throw new IOException();
					}

					while( parser.nextToken() != JsonToken.END_ARRAY ) {
						Class< ? > loaderClazz = Class.forName( parser.getText() );
						loader = ( PlayerLoader )loaderClazz.newInstance();
					}
					break;
				case "saver":
					if( parser.nextToken() != JsonToken.START_ARRAY ) {
						throw new IOException();
					}

					while( parser.nextToken() != JsonToken.END_ARRAY ) {
						Class< ? > saverClazz = Class.forName( parser.getText() );
						saver = ( PlayerSaver )saverClazz.newInstance();
					}
					break;
			}
		}

	}


	/**
	 * Submits a login request.
	 * @param session The session submitting this request.
	 * @param request The login request.
	 * @param fileSystem The file system
	 * @throws IOException If some I/O exception occurs.
	 */
	public void submitLoadRequest( LoginSession session, LoginRequest request, IndexedFileSystem fileSystem ) throws IOException
	{
		if( requiresUpdate( request, fileSystem ) ) {
			session.handlePlayerLoaderResponse( request, new PlayerLoaderResponse( LoginConstants.STATUS_GAME_UPDATED ) );
		} else {
			executor.submit( new PlayerLoaderWorker( loader, session, request ) );
		}
	}


	/**
	 * Checks if an update is required whenever a {@link Player} submits a login request.
	 * @param request The login request.
	 * @oaram fileSystem The file system.
	 * @return {@code true} if an update is required, otherwise return {@code false}.
	 * @throws IOException If some I/O exception occurs.
	 */
	private boolean requiresUpdate( LoginRequest request, IndexedFileSystem fileSystem ) throws IOException
	{
		if( GameConstants.VERSION != request.getCurrentVersion() ) {
			return true;
		}
		ByteBuffer buffer = fileSystem.getCrcTable();
		int[] crcs = request.getArchiveCrcs();
		if( buffer.remaining() < crcs.length ) {
			return true;
		}
		for( int crc: crcs ) {
			if( crc != buffer.getInt() ) {
				return true;
			}
		}
		return false;
	}


	/**
	 * Submits a save request.
	 * @param session The session submitting this request.
	 * @param player The player to save.
	 */
	public void submitSaveRequest( GameSession session, Player player )
	{
		executor.submit( new PlayerSaverWorker( saver, session, player ) );
	}


	@Override
	public void start()
	{
		/* empty - here for consistency with other services */
	}

}
