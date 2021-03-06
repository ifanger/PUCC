package threads;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Scanner;

import com.google.gson.Gson;

import daos.Players;
import db.DB;
import game.PlayerHandler;
import protocol.GFProtocol;
import protocol.Player;
import protocol.Ranking;
import server.Game;
import server.SocketHandler;

/**
 * Classe respons�vel pelo gerenciamento de um �nico cliente.
 * @author Gustavo Ifanger.
 *
 */
public class ClientThread extends Thread {
	protected Socket 					socket 			= null;
	protected ArrayList<ClientThread>	clientList		= null;
	protected SocketHandler				handler			= null;
	protected Game						game			= null;
	protected BufferedReader			input			= null;
	protected Gson						gson			= null;
	protected PlayerHandler				player 			= null;
	protected boolean					connected		= false;
	protected Ranking					ranking			= null;
	
	/**
	 * Construtor padr�o.
	 * @param socket Socket do cliente.
	 * @param clientList Lista de clientes.
	 * @param handler Manuseador do Socket.
	 * @param game Inst�ncia do jogo.
	 * @param ranking �ltimo ranking gerado pelo servidor.
	 */
	public ClientThread(Socket socket, ArrayList<ClientThread> clientList, SocketHandler handler, Game game, Ranking ranking)
	{
		this.socket		= socket;
		this.clientList	= clientList;
		this.handler	= handler;
		this.game		= game;
		this.gson		= new Gson();
		this.connected	= true;
		this.ranking	= ranking;
		
		try {
			this.input = new BufferedReader(new InputStreamReader(socket.getInputStream()));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@Override
	public void run()
	{
		try
		{
			String receivedPacket = "";
			while(this.socket.isConnected() && (receivedPacket = this.input.readLine()) != null)
			{
				int packetType = GFProtocol.getPacketType(receivedPacket);
				if(packetType == GFProtocol.PacketType.RANKING)
				{
					try {
						ranking = Players.getRanking();
					} catch(Exception e) {}
					this.handler.sendMessage(String.format(GFProtocol.RANKING_INFORMATION, gson.toJson(ranking)));
				} else if(packetType == GFProtocol.PacketType.LOGIN)
				{
					if(player != null)
						return;
					
					Player receivedPlayer = GFProtocol.getPlayerFromLoginPacket(receivedPacket);
					boolean success = false;
					Player dbPlayer = null;
					
					if(receivedPlayer != null)
					{
						try {
							dbPlayer = Players.getPlayer(receivedPlayer.getEmail());
							
							if(dbPlayer.getPassword().equals(receivedPlayer.getPassword()))
							{
								this.player = new PlayerHandler(dbPlayer, this);
								success = true;
							}
						} catch (Exception e) {
							success = false;
						}
					}
					
					this.sendPacket(String.format(GFProtocol.LOGIN_RESPONSE, ((success) ? gson.toJson(dbPlayer) : "F" )));
					
					if(success && this.player != null)
						game.onPlayerJoined(player);
				} else if(packetType == GFProtocol.PacketType.REGISTER)
				{
					Player newPlayer = GFProtocol.getPlayerFromRegisterPacket(receivedPacket);
					boolean success = false;
					
					try
					{
						Players.register(newPlayer);
						success = true;
					} catch(Exception e)
					{
						success = false;
					}
					
					this.sendPacket(String.format(GFProtocol.REGISTER_RESPONSE, ((success) ? "T" : "F")));
				} else if(packetType == GFProtocol.PacketType.BINGO)
				{
					if(player == null)
					{
						System.out.println("Pediram bingo de maneira incorreta.");
						return;
					}
					
					game.onPlayerBingo(player);
				}
				else
				{
					System.out.println("Pacote estranho recebido.");
					this.sendPacket(GFProtocol.KICK);
					this.disconnect();
				}
			}
		} catch(Exception e) {
			this.disconnectPlayer();
		}
	}
	
	/**
	 * Desconecta um jogador.
	 */
	public void disconnectPlayer()
	{
		if(socket == null || input == null)
			return;
		
		this.disconnect();
		this.game.onPlayerLeft(player);
	}

	/**
	 * Envia pacote ao cliente.
	 * @param packet Pacote a ser enviado.
	 */
	public void sendPacket(String packet)
	{
		this.handler.sendMessage(packet);
	}
	
	/**
	 * Encerra conex�o com o cliente.
	 */
	public void disconnect()
	{
		this.sendPacket(GFProtocol.KICK);
		this.handler.disconnect();
		try {
			this.socket.close();
			this.input.close();
			
			this.socket = null;
			this.input = null;
		} catch (IOException e) {}
		this.connected = false;
	}

}
