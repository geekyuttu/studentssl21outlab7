package bobby;

import java.net.*;
import java.io.*;
import java.util.*;

import java.util.concurrent.Semaphore;

// import jdk.internal.icu.text.NormalizerBase.Mode;



public class ServerThread implements Runnable{
	private Board board;
	private int id;
	private boolean registered;
	private BufferedReader input;
	private PrintWriter output;
	private Socket socket;
	private int port;
	private int gamenumber;

	public ServerThread(Board board, int id, Socket socket, int port, int gamenumber){
		
		this.board = board;

		//id from 0 to 4 means detective, -1 means fugitive
		this.id = id;
		
		this.registered = false;

		this.socket = socket;
		this.port = port;
		this.gamenumber = gamenumber;
	}

	public void run(){

		try{

			/*
			PART 0_________________________________
			Set the sockets up
			*/

			try{
				input = new BufferedReader(new InputStreamReader(socket.getInputStream()));
				output = new PrintWriter(socket.getOutputStream(),true);                                  
				
				if (this.id == -1) {
					output.println(String.format(
							"Welcome. You play Fugitive in Game %d:%d. You start on square 42. Make a move, and wait for feedback",
							this.port, this.gamenumber));
				} else {
					output.println(String.format(
							"Welcome. You play Detective %d in Game %d:%d. You start on square 0. Make a move, and wait for feedback",
							this.id, this.port, this.gamenumber));
				}
			}
			catch (Exception i){
				/*
				there's no use keeping this thread, so undo what the
				server did when it decided to run it
				*/
				if(this.id != -1){    
					this.board.totalThreads--;
					this.board.availableIDs[this.id] = true;      
				}  
                                             
				return;
			}

			//__________________________________________________________________________________________

			while(true){

				boolean quit = false;
				boolean client_quit = false;
				boolean quit_while_reading = false;
				int target = -1;

				/*
				client_quit means you closed the socket when you read the input,
				check this flag while making a move on the board

				quit means that the thread decides that it will not play the next round

				if quit_while_reading, you just set the board to dead if 
				you're a fugitive. Don't edit the positions on the board, as threads
				are reading

				quit_while_reading is used when you can't call erasePlayer just yet, 
				because the board is being read by other threads

				INVARIANT: 
				quit == client_quit || quit_while_reading

				client_quit && quit_while_reading == false

				DO NOT edit board between barriers!

				erasePlayer, when called by exiting Fugitive, sets 
				this.board.dead to true. Make sure to only alter it during the
				round

				either when
				1) you're about to play but the client had told you to quit
				2) you've played and the game got over in this round.

				________________________________________________________________________________________
				
				First, base case: Fugitive enters the game for the first time
				installPlayer called by a fugitive sets embryo to false.

				Register the Fugitive, install, and enable the moderator, and 
				continue to the next iteration
				*/
				
				if (this.id == -1 && !this.registered){
                                                             
					this.board.registration.acquire();
					this.board.reentry.acquire();
					this.registered = true;
					this.board.installPlayer(this.id);     
					this.board.moderatorEnabler.release();

                    // System.out.println("this.board.embryo = " + this.board.embryo);

					continue;
				}

				/*
				Now, usual service
				
				
				PART 1___________________________
				read what the client has to say.
				
				totalThreads and quitThreads are relevant only to the moderator, so we can 
				edit them in the end, just before
				enabling the moderator. (we have the quit flag at our disposal)

				For now, if the player wants to quit,
				just make the id available, by calling erasePlayer.
				this MUST be called by acquiring the threadInfoProtector! 
				
				After that, say goodbye to the client if client_quit
				*/

				String cmd = "";
				try {
					// System.out.println("we are taking in command");
					cmd = input.readLine();
				}
				catch (IOException i) {
					//set flags
					client_quit = true;
					quit = true;
					// if(this.id == -1) this.board.dead = true;
 					
					// release everything socket related
					socket.close();
                    input.close();
					output.close();
                    
				}

				if (cmd == null){
					// rage quit (this would happen if buffer is closed due to SIGINT (Ctrl+C) from Client), set flags
					client_quit = true;  
					quit = true;
					// if(this.id == -1) this.board.dead = true;

					// release everything socket related
					socket.close();
					input.close();
					output.close();

				}
				
				else if (cmd.equals("Q")) {
					// client wants to disconnect, set flags
					client_quit = true;
					quit = true;	
					// if(this.id == -1) this.board.dead = true;
	
					// release everything socket related            
					socket.close();
					input.close();
					output.close();
                    
				}

				else{
					try{
						//interpret input as the integer target
						target = Integer.parseInt(cmd);
					}
					catch(Exception e){
						//set target that does nothing for a mispressed key
						target = -1;
					}
				}

				/*
				In the synchronization here, playingThreads is sacrosanct.
				DO NOT touch it!
				
				Note that the only thread that can write to playingThreads is
				the Moderator, and it doesn't have the permit to run until we 
				are ready to cross the second barrier.
				
				______________________________________________________________________________________
				PART 2______________________
				entering the round

				you must acquire the permit the moderator gave you to enter, 
				regardless of whether you're new.

				Also, if you are new, check if the board is dead. If yes, erase the player, set the
				flags, and drop the connection

				Note that installation of a Fugitive sets embryo to false
				*/				
				if (!this.registered){
					
					this.board.registration.acquire();
					this.board.reentry.acquire();
					this.registered = true;
					this.board.installPlayer(this.id);
					
					if(this.board.dead){

						this.board.erasePlayer(this.id);
						quit = true;
						this.board.registration.release();
						this.registered = false;

						socket.close();
						input.close();
						output.close();

					}
                                              
				}else{
					this.board.reentry.acquire();
					// System.out.println("Available prmits: " + this.board.reentry.availablePermits());
				}
				
				/*
				_______________________________________________________________________________________
				PART 3___________________________________
				play the move you read in PART 1 
				if you haven't decided to quit

				else, erase the player
				*/
				this.board.threadInfoProtector.acquire();

				if(!client_quit){
					if(this.id == -1){
						this.board.moveFugitive(target);
						// System.out.println("Fugitive moved to" + target);
					}
					else{
						this.board.moveDetective(this.id, target);
						// System.out.println("Detective moved to" + target);
					}
				}else{
					this.board.erasePlayer(this.id);
				}
                                                                         
				this.board.threadInfoProtector.release();
                                      
      

                                                
      

                                              
     

         
                                              
                                     
                                              
     
 			
				/*

				_______________________________________________________________________________________

				PART 4_____________________________________________
				cyclic barrier, first part
				
				execute barrier, so that we wait for all playing threads to play

				Hint: use the count to keep track of how many threads hit this barrier
				they must acquire a permit to cross. The last thread to hit the barrier can 
				release permits for them all.
				*/       
				
				this.board.countProtector.acquire();
				this.board.count++;                                       
			
				if(this.board.count == this.board.playingThreads) this.board.barrier1.release(this.board.playingThreads);
				
				this.board.countProtector.release();

				this.board.barrier1.acquire();
     
				// System.out.println("hey am here");
                                        
                                  

				/*
				________________________________________________________________________________________

				PART 5_______________________________________________
				get the State of the game, and process accordingly. 

				recall that you can only do this if you're not walking away, you took that
				decision in PARTS 1 and 2

				It is here that everyone can detect if the game is over in this round, and decide to quit
				*/

				if (!client_quit){
					String feedback;
					                                         
                    if(this.id == -1){
						feedback = this.board.showFugitive();
					}else{
						feedback = this.board.showDetective(this.id);
					}
                                              

					//pass this to the client via the socket output
					try{
						output.println(feedback);
					}
					//in case of IO Exception, off with the thread
					catch(Exception i){
						//set flags 
						quit_while_reading = true;                          
						quit = true;

						// If you are a Fugitive you can't edit the board, but you can set dead to true
						if(this.id == -1){
							                                         
                              this.board.dead = true;
                                                
						}

						// release everything socket related            
						output.close();
						input.close();
						socket.close();  

					}

					
					
					//parse this feedback to find if game is on
					String indicator;
					indicator = feedback.split("; ")[2];


					if (!indicator.equals("Play")){
						//Proceed simillarly to IOException
						
						//set flags 
						quit = true;                          
                  
						// If you are a Fugitive you can't edit the board, but you can set dead to true
						if(this.id == -1){
							                                         
                              this.board.dead = true;
                                                
						}

						// release everything socket related
						input.close();
						output.close();
						socket.close();  						                                               
                     
					}
				}

				/*
				__________________________________________________________________________________
				PART 6A____________________________
				wrapping up


				everything that could make a thread quit has happened
				now, look at the quit flag, and, if true, make changes in
				totalThreads and quitThreads
				*/

				if(quit == true){
					this.board.threadInfoProtector.acquire();
					this.board.quitThreads++;
					this.board.totalThreads--;
					this.board.threadInfoProtector.release();
				}          
                                              
                                
                              
                                              
     

				/*
				__________________________________________________________________________________
				PART 6B______________________________
				second part of the cyclic barrier
				that makes it reusable
				
				our threads must wait together before proceeding to the next round

				Reuse count to keep track of how many threads hit this barrier2 

				The code is similar. However, the last thread to hit this barrier must also 
				permit the moderator to run
				*/

				// System.out.println("Board count = " + this.board.count);
                this.board.countProtector.acquire();       
                this.board.count--;
                                                            
                if(this.board.count == 0) {
					this.board.barrier2.release(this.board.playingThreads);                           
					this.board.moderatorEnabler.release();
				}

				this.board.countProtector.release();                

				// System.out.println("I am stuck here " + this.id);
				this.board.barrier2.acquire();                                    
				// System.out.println("I am going to the next loop " + this.id);
                                        
                                  

				/*
				__________________________________________________________________________________
				PART 6C_________________________________
				actually finishing off a thread
				that decided to quit

				If you quit while reading, now is the time to erase the player
				*/
				                          
				if(quit_while_reading){
					this.board.erasePlayer(this.id);
				}                                                   
                          
                if(quit) break;      
     
			}
		}
		catch (InterruptedException ex) {
			return;
		}
		catch (IOException i){
			return;
		}
	}

	
}