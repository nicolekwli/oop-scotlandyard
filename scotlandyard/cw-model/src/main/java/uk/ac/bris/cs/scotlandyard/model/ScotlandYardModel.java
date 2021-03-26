package uk.ac.bris.cs.scotlandyard.model;

import static java.util.Objects.requireNonNull;
import static uk.ac.bris.cs.scotlandyard.model.Colour.BLACK;
import static uk.ac.bris.cs.scotlandyard.model.Ticket.DOUBLE;
import static uk.ac.bris.cs.scotlandyard.model.Ticket.SECRET;
import uk.ac.bris.cs.scotlandyard.model.Spectator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import uk.ac.bris.cs.gamekit.graph.Edge;
import uk.ac.bris.cs.gamekit.graph.Graph;
import uk.ac.bris.cs.gamekit.graph.ImmutableGraph;
import uk.ac.bris.cs.gamekit.graph.Node;

/**
 * A class that models a Scotland Yard game.
 */
public class ScotlandYardModel implements ScotlandYardGame, Consumer<Move> {
	private final List<Boolean> rounds;
	private final Graph<Integer, Transport> graph;
	private List<ScotlandYardPlayer> listPlayers = new CopyOnWriteArrayList<ScotlandYardPlayer>();
	private Colour currentPlayer;
	private int currentRound;
	private Set<Move> validMoves;
	private int mrXLastLocation;
	private int mrXLastKnownLocation;
	private boolean hasBeenRevealedBefore;
	private List<Spectator> spectators = new CopyOnWriteArrayList<>();
	private Set<Colour> winners;

	public ScotlandYardModel(List<Boolean> rounds, Graph<Integer, Transport> graph,PlayerConfiguration mrX, PlayerConfiguration firstDetective,	PlayerConfiguration... restOfTheDetectives) {
		if (rounds.isEmpty()) 
			throw new IllegalArgumentException("Empty rounds");
		else  this.rounds = requireNonNull(rounds);

		if (graph.isEmpty()) 
			throw new IllegalArgumentException("Empty graph");
		else  this.graph = requireNonNull(graph);
		
		if (mrX.colour.isDetective())
			throw new IllegalArgumentException("MrX should be Black");

		if (firstDetective.colour.isMrX())
			throw new IllegalArgumentException("Detective should not be Black");
		
		this.listPlayers = createNewListPlayers(mrX, firstDetective, restOfTheDetectives); 	//create list of players
		this.currentPlayer = BLACK;
		this.currentRound = NOT_STARTED;
		this.mrXLastLocation = 0;
		this.mrXLastKnownLocation = 0;
		this.hasBeenRevealedBefore = rounds.get(0);
		this.winners = new HashSet<>();

		checkTickets();
		checkLocation();
		checkColour();
	}

//----------------------------------------------------------------------------------------------------------------------------
// Functions related to spectators
	@Override
	public void registerSpectator(Spectator spectator) {
		for (Spectator s : spectators){
			if (s == spectator)
				throw new IllegalArgumentException("Duplicate spectator");
		}
		this.spectators.add(requireNonNull(spectator));
	}

	@Override
	public void unregisterSpectator(Spectator spectator) {
		if (getSpectators().contains(requireNonNull(spectator)))
			this.spectators.remove(spectator);
		else throw new IllegalArgumentException("No spectator");
	}

	@Override
	public Collection<Spectator> getSpectators() {
		return Collections.unmodifiableList(this.spectators);
	}

	// Notify spectators on game over
	private void notifyGameOver(){
		for (Spectator s : spectators){
			s.onGameOver(this, this.winners);
		}	
	}

	// Notify spectators on move made
	private void notifyMove(Move move){
		for (Spectator s : spectators){
			s.onMoveMade(this, move);
		}
	}

	// Notify spectators on a new round, also updates attributes accordingly
	private void notifyRoundStart(){
		currentRound++;
		for (Spectator s : spectators){
			s.onRoundStarted(this, getCurrentRound());
		}
		if ((getRounds().get(getCurrentRound()-1))){
			hasBeenRevealedBefore = true;
			mrXLastKnownLocation = getScotlandYardPlayer(this.currentPlayer).get().location();
		}
	}

//----------------------------------------------------------------------------------------------------------------------------
	@Override
	public void startRotate(){
		requestMakeMove();	
	}

	private void requestMakeMove(){
		if (isGameOver())
			throw new IllegalStateException("GAME OVER");
		
		int loc = getScotlandYardPlayer(getCurrentPlayer()).get().location();
		ScotlandYardPlayer p = getScotlandYardPlayer(this.currentPlayer).get();
		this.validMoves = validMoves(getCurrentPlayer());
		p.player().makeMove(this, loc, this.validMoves, this);	
	}

	@Override
	public void accept(Move move){
		if (move == null) 
			throw new NullPointerException("Move is null");
		else if (!this.validMoves.contains(move)) 
			throw new IllegalArgumentException("Invalid move");

		MoveVisitor theChosenMove = new DesignPattern();
		move.visit(theChosenMove);

		this.validMoves = validMoves(BLACK);
		if (isGameOver())
			notifyGameOver();

		else {
			if (this.currentPlayer != BLACK)
			requestMakeMove();
			else{	
				for (Spectator s : spectators){
					s.onRotationComplete(this);
				}
			}
		}
	}

//----------------------------------------------------------------------------------------------------------------------------
// Functions related to getting the valid moves
	// Removes moves for detectives if the destination is occupied by a detective but keeps the moves if its mrX
	// Removes moves for mrX if destination is occupied by a detective
	private Set<Edge<Integer, Transport>> movesWithValidDestination(Set<Edge<Integer, Transport>> moves){
		Set<Edge<Integer, Transport>> removeThese = new HashSet<>();
		for(Edge<Integer, Transport> anEdge : moves){
			for (ScotlandYardPlayer l : this.listPlayers) {
				if (l.colour() != this.currentPlayer) {
					if (l.location() == anEdge.destination().value()) {
						if (!((currentPlayer.isDetective()) && (l.isMrX()))) // !(Only condition to keep move when locations overlap: to catch mrX)
							removeThese.add(anEdge);
					}
				}
			}
		}
		moves.removeAll(removeThese);
		return moves;
	}

	// Removes moves that the current player does not have tickets for
	private Set<Edge<Integer, Transport>> checkPlayerHasTickets(Set<Edge<Integer, Transport>> possibleMoves){
		Set<Edge<Integer, Transport>> removeThese = new HashSet<>();
		ScotlandYardPlayer p =  getScotlandYardPlayer(this.currentPlayer).get();
		for(Edge<Integer, Transport> anEdge : possibleMoves){
			Ticket t = Ticket.fromTransport(anEdge.data());
			if (!p.hasTickets(t, 1)) removeThese.add(anEdge);
		}
		possibleMoves.removeAll(removeThese);
		return possibleMoves;
	}

	// Removes double moves that the current player does not have tickets for
	private Set<DoubleMove> checkPlayerHasTicketsForDoubleMove(Set<DoubleMove> possibleMoves){
		Set<DoubleMove> removeThese = new HashSet<>();
		ScotlandYardPlayer p =  getScotlandYardPlayer(this.currentPlayer).get();
		for(DoubleMove m : possibleMoves){
			Ticket firstTicket = m.firstMove().ticket();
			Ticket secondTicket = m.secondMove().ticket();
			if (firstTicket == secondTicket){
				if (!p.hasTickets(firstTicket, 2)) removeThese.add(m);
			}
			else if ((!p.hasTickets(firstTicket, 1)) || (!p.hasTickets(secondTicket, 1)))
				removeThese.add(m);
		}
		possibleMoves.removeAll(removeThese);
		return possibleMoves;
	}

	// Convert edges to moves according to transport type, converts all to secret move if required
	private Set<TicketMove> convertToTicketMove(Set<Edge<Integer, Transport>> possibleMoves, Boolean isSecret){
		Set<TicketMove> validMoves = new HashSet<>();
		for(Edge<Integer, Transport> anEdge : possibleMoves){
			TicketMove realMove = null;
			if (isSecret)
				realMove = new TicketMove(this.currentPlayer, Ticket.SECRET, anEdge.destination().value());
			else
				realMove = new TicketMove(this.currentPlayer, Ticket.fromTransport(anEdge.data()), anEdge.destination().value());
			validMoves.add(realMove);
		}
		return validMoves;
	}

	// Creates double moves, returns a set of moves with the same first move
	private Set<DoubleMove> convertToDoubleMove(Edge<Integer, Transport> firstPossibleMove, Set<Edge<Integer, Transport>> secondPossibleMoves) {
		Set<DoubleMove> validMoves = new HashSet<>();
		TicketMove firstMove = new TicketMove(this.currentPlayer, Ticket.fromTransport(firstPossibleMove.data()), firstPossibleMove.destination().value());
		for(Edge<Integer, Transport> anEdge : secondPossibleMoves){
			TicketMove secondMove = new TicketMove(this.currentPlayer, Ticket.fromTransport(anEdge.data()), anEdge.destination().value());
			DoubleMove realMove = new DoubleMove(this.currentPlayer, firstMove, secondMove);
			validMoves.add(realMove);
		}
		return validMoves;
	}

	// Creates double moves, returns a set of moves where first move is a secret or a set where second move is a secret
	private Set<DoubleMove> convertToDoublesWithSecrets(TicketMove firstMove, Set<Edge<Integer, Transport>> secondPossibleMoves, Boolean isFirst){
		Set<DoubleMove> validMoves = new HashSet<>();
		TicketMove secondMove = null;
		for(Edge<Integer, Transport> anEdge : secondPossibleMoves){
			if (isFirst){
				secondMove = new TicketMove(this.currentPlayer, Ticket.fromTransport(anEdge.data()), anEdge.destination().value());
			}
			else
				secondMove = new TicketMove(this.currentPlayer, Ticket.SECRET, anEdge.destination().value());
			DoubleMove realMove = new DoubleMove(this.currentPlayer, firstMove, secondMove);
			validMoves.add(realMove);
		}
		return validMoves;
	}

	// Gets double moves, returns a set where one secret move is used
	private Set<DoubleMove> getDoublesWithSecrets(Set<TicketMove> validMoves, Boolean isFirst){
		Graph<Integer, Transport> currentGraph = getGraph();
		Set<DoubleMove> doubleMoves = new HashSet<>();
		for (TicketMove m : validMoves){
			Node<Integer> firstDestinationNode = new Node<Integer>(m.destination());
			Set<Edge<Integer, Transport>> secondPossibleMoves = new HashSet<>();
			secondPossibleMoves.addAll(currentGraph.getEdgesFrom(firstDestinationNode));
			secondPossibleMoves = movesWithValidDestination(secondPossibleMoves);
			if (isFirst)
				doubleMoves.addAll(convertToDoublesWithSecrets(m, secondPossibleMoves, true));
			else
				doubleMoves.addAll(convertToDoublesWithSecrets(m, secondPossibleMoves, false));
			 
		}
		doubleMoves = checkPlayerHasTicketsForDoubleMove(doubleMoves);
		return doubleMoves;
	}

	// Creates double moves where both moves uses a secret ticket
	private Set<DoubleMove> fromDoubleToSecret(Set<DoubleMove> moves){
		Set<DoubleMove> doubleMoves = new HashSet<>();
		for (DoubleMove m : moves){
			TicketMove first = m.firstMove(), second = m.secondMove();
			TicketMove newFirst = new TicketMove(this.currentPlayer, Ticket.SECRET, first.destination());
			TicketMove newSecond = new TicketMove(this.currentPlayer, Ticket.SECRET, second.destination());
			DoubleMove d = new DoubleMove(this.currentPlayer, newFirst, newSecond);
			doubleMoves.add(d);
		}
		return doubleMoves;
	}

	// Gets double moves without secret tickets
	private Set<DoubleMove> getDoubleMoves(Set<Edge<Integer, Transport>> validMoves){
		Graph<Integer, Transport> currentGraph = getGraph();
		Set<DoubleMove> doubleMoves = new HashSet<>();
		for (Edge<Integer, Transport> m : validMoves){
			Node<Integer> firstDestinationNode = new Node<Integer>(m.destination().value());
			Set<Edge<Integer, Transport>> secondPossibleMoves = new HashSet<>();
			secondPossibleMoves.addAll(currentGraph.getEdgesFrom(firstDestinationNode));
			secondPossibleMoves = movesWithValidDestination(secondPossibleMoves);
			doubleMoves.addAll(convertToDoubleMove(m, secondPossibleMoves));
		}
		return doubleMoves;
	} 

	// Sets moves to having only one pass move
	private Set<Move> setToPassMove() {
		Set<Move> validMoves = new HashSet<>();
		Move realMove = new PassMove(this.currentPlayer);
			validMoves.add(realMove);
		return Collections.unmodifiableSet(validMoves);
	}

	// Gets moves that contain secrets and doubles for mrX
	private Set<Move> validMovesForMrX(Graph<Integer, Transport> currentGraph, Node<Integer> currentNode,
									  Set<Edge<Integer, Transport>> possibleMoves){
		Set<Move> validMoves = new HashSet<>();
		Set<TicketMove> secretMoves = new HashSet<>();
		Set<DoubleMove> doubleMoves = new HashSet<>();
		Map<Ticket, Integer> playerTickets = getScotlandYardPlayer(BLACK).get().tickets();
		int secret = playerTickets.get(Ticket.SECRET);
		int doubles = playerTickets.get(Ticket.DOUBLE);
		
		if (secret != 0){
			secretMoves.addAll(convertToTicketMove(movesWithValidDestination(possibleMoves), true));
		}

		if (doubles != 0){
			if ((this.rounds.size() - this.currentRound) >= 2){
				doubleMoves.addAll(getDoubleMoves(movesWithValidDestination(possibleMoves)));
				// Deal with double moves that include secret moves
				if (secret > 0){
					validMoves.addAll(getDoublesWithSecrets(secretMoves, true));
					validMoves.addAll(getDoublesWithSecrets(convertToTicketMove(movesWithValidDestination(possibleMoves),false),false));
					if (secret >= 2)
						validMoves.addAll(fromDoubleToSecret(doubleMoves));
				}
				validMoves.addAll(checkPlayerHasTicketsForDoubleMove(doubleMoves));
			}
		}
		validMoves.addAll(secretMoves);
		return validMoves;
	}

	// Gets a set of valid moves
	private Set<Move> validMoves(Colour player) {
		Set<Move> validMoves = new HashSet<>();
		Node<Integer> currentNode = new Node<Integer>(getScotlandYardPlayer(getCurrentPlayer()).get().location());
		Collection<Edge<Integer, Transport>> immutablePossibleMoves = new HashSet<>();
		Set<Edge<Integer, Transport>> possibleMoves = new HashSet<>();

		immutablePossibleMoves = getGraph().getEdgesFrom(currentNode);
		possibleMoves.addAll(immutablePossibleMoves);
		
		if (player.isDetective()){ // For detectives
			possibleMoves = checkPlayerHasTickets(movesWithValidDestination(possibleMoves));
			if (possibleMoves.isEmpty()){
				validMoves = setToPassMove(); 	//PassMove when a detective is stuck
				return validMoves;
			}
		}
		
		else{ // For mrX
			// This is for secret and double moves
			validMoves.addAll(validMovesForMrX(getGraph(), currentNode, possibleMoves));
			// This is for normal ticket moves
			possibleMoves = checkPlayerHasTickets(movesWithValidDestination(possibleMoves));
			validMoves.addAll(convertToTicketMove(requireNonNull(possibleMoves), false));
			return Collections.unmodifiableSet(validMoves);
		}
		validMoves.addAll(convertToTicketMove(requireNonNull(checkPlayerHasTickets(possibleMoves)), false));
		return Collections.unmodifiableSet(validMoves);
	}
	
//----------------------------------------------------------------------------------------------------------------------------
	class DesignPattern implements MoveVisitor{
		private void decrementTicket(ScotlandYardPlayer thePlayer, Ticket modeOfTransport){
			thePlayer.removeTicket(modeOfTransport);
			if (thePlayer.isDetective())	
				(listPlayers.get(0)).addTicket(modeOfTransport);
		}

		@Override
		public void visit(PassMove move) {
			currentPlayer = incrementPlayer();
			notifyMove(move);	
		}

		@Override
		public void visit(TicketMove move) {
			ScotlandYardPlayer thePlayer =  getScotlandYardPlayer(currentPlayer).get();
			Ticket modeOfTransport = move.ticket();
			decrementTicket(thePlayer, modeOfTransport);

			thePlayer.location(move.destination());
			currentPlayer = incrementPlayer();

			if (thePlayer.colour() == BLACK){
				mrXLastLocation = move.destination();
				notifyRoundStart();
				move = new TicketMove(thePlayer.colour(), modeOfTransport, getPlayerLocation(BLACK).get());			
			}
			else
				validMoves = validMoves(BLACK);
			notifyMove(move);
		}

		@Override
		public void visit(DoubleMove move) {
			ScotlandYardPlayer thePlayer =  getScotlandYardPlayer(currentPlayer).get();
			mrXLastLocation = move.finalDestination();

			TicketMove move1 = move.firstMove(), move2 = move.secondMove();
			Ticket t1 = move1.ticket(), t2 = move2.ticket();
			decrementTicket(thePlayer, DOUBLE);

			currentPlayer = incrementPlayer();

			Integer firstLocation = 0;
			Integer secondLocation = move2.destination(); 
			if (!(getRounds().get(getCurrentRound()))){
				if (hasBeenRevealedBefore)
					firstLocation = mrXLastKnownLocation;
	
				if (!(getRounds().get(getCurrentRound()+1))){
					if (!hasBeenRevealedBefore)
						secondLocation = 0;
					else
						secondLocation = move1.destination();
				}
			}

			if ((getRounds().get(getCurrentRound()))){
				firstLocation = move1.destination();
				mrXLastKnownLocation = firstLocation;
				if (!(getRounds().get(getCurrentRound()+1)))
					secondLocation = move1.destination();
			}
			DoubleMove newMove = new DoubleMove(BLACK, t1, firstLocation, t2, secondLocation);

			notifyMove(newMove);
			decrementTicket(thePlayer, t1);
			thePlayer.location(move.firstMove().destination());
			notifyRoundStart();
			
			notifyMove(newMove.firstMove());
			decrementTicket(thePlayer, t2);
			thePlayer.location(move.secondMove().destination());
			notifyRoundStart();

			notifyMove(newMove.secondMove());
		}
	}

//-----------------------------------------------------------------------------------------------------------------------------
	// Returns a ScotlandYardPlayer given a colour
	private Optional<ScotlandYardPlayer> getScotlandYardPlayer(Colour colour){
		for (ScotlandYardPlayer l : this.listPlayers) {
			if (l.colour() == colour) return Optional.of(l);
		}
		return Optional.empty();
	}

	// If winner is Black, gets the winning players
	private void findWinners(Colour player){
		this.winners.add(player);
	}

	// If detectives are winners, gets the winning players
	private void findWinners(){
		for (ScotlandYardPlayer p : this.listPlayers){
			if (p.isDetective())
				this.winners.add(p.colour());
		}
	}
	
	// Sets next player as current player
	private Colour incrementPlayer(){
		for (ScotlandYardPlayer p : this.listPlayers){
			if (p.colour() == this.currentPlayer){
				int i = this.listPlayers.indexOf(p);
				if (i == (this.listPlayers.size() - 1) ){
					i = -1;
				} 
				return (this.listPlayers.get(i+1)).colour();
			}
		}
		return this.currentPlayer;
	}

	@Override
	public List<Colour> getPlayers() {
		List<Colour> colours = new ArrayList<>();
		for (ScotlandYardPlayer l : this.listPlayers){
			colours.add(l.colour());
		}
		return Collections.unmodifiableList(colours);
	}

	@Override
	public Set<Colour> getWinningPlayers() {
		Set<Colour> set = new HashSet<>();
		if (isGameOver()){
			set.addAll(this.winners);
		}
		return Collections.unmodifiableSet(set);
	}

    @Override
    public Optional<Integer> getPlayerLocation(Colour colour) { 
        Optional<Integer> op = Optional.empty();

        for (ScotlandYardPlayer l : this.listPlayers){
            if (l.colour() == colour){
                if (colour == BLACK){
					if (getCurrentRound() == 0){
						op = Optional.of(0);
					}
					else {	//hidden round
						if ((getRounds().get(getCurrentRound()-1)) == false) {
							if (!hasBeenRevealedBefore)
								op = Optional.of(0);
							else
								op = Optional.of(mrXLastKnownLocation);
						}
						
						else { // reveal round
							op = Optional.of(l.location());
							mrXLastKnownLocation = l.location();
							this.hasBeenRevealedBefore = true;				
						}
					}	
				}
				else return Optional.of(l.location());  
			}
		}
        return op;
    }

	@Override
	public Optional<Integer> getPlayerTickets (Colour colour, Ticket ticket) {
		Optional<Integer> op = Optional.empty();
		for (ScotlandYardPlayer l : this.listPlayers){
			if ( (l.colour() == colour) && (l.hasTickets(ticket, 0)) ) {
				int value = (l.tickets()).get(ticket); 	//gets map from l.tickets, then gets the values from 'ticket' 
				op = Optional.of(value);
				return op;
			}
		}
		return op;
	}

	@Override
	public Colour getCurrentPlayer() {
		return requireNonNull(this.currentPlayer);	
	}

	@Override
	public int getCurrentRound() {
		if (this.currentRound == NOT_STARTED)
			return 0;
		return this.currentRound;
	}

	@Override
	public List<Boolean> getRounds() {
		return Collections.unmodifiableList(rounds);
	}

	@Override
	public Graph<Integer, Transport> getGraph() {
		Graph<Integer, Transport> g = new ImmutableGraph<Integer, Transport>(graph);
		return g;
	}

	@Override
	public boolean isGameOver() {
		boolean haveTickets = false;
		// When the last round is reached, MrX wins
		if ((this.getCurrentRound() == rounds.size()) && (this.currentPlayer == BLACK)){
			findWinners(BLACK);
			return true;
		}

		for (ScotlandYardPlayer p : this.listPlayers){
			if (p.isDetective()){
				// When MrX is caught
				if ((p.location() == this.mrXLastLocation)){
					findWinners();
					return true;
				}
				// Check if detectives have no more tickets
				for (Ticket t : Ticket.values()){
					if (p.hasTickets(t))
						haveTickets = true;
				}
			}
		}
		
		// MrX wins if a detective runs out of tickets
		if (haveTickets == false){
			findWinners(BLACK);
			return true;
		}

		// If MrX is stuck
		if ((this.currentPlayer == BLACK) && (this.currentRound != 0)){
			if (this.validMoves.isEmpty()){
				findWinners();
				return true;
			}
		}
		return false;
	}
//-----------------------------------------------------------------------------------------------------------------------------
	private void checkLocation(){
		Set<Integer> set = new HashSet<>();
		for (ScotlandYardPlayer p : this.listPlayers) {
			if (set.contains(p.location()))
				throw new IllegalArgumentException("Duplicate location");
			set.add(p.location());
		}
	} 

	private void checkColour(){
		Set<Colour> set = new HashSet<>();
		for (ScotlandYardPlayer p : this.listPlayers) {
		if (set.contains(p.colour()))
			throw new IllegalArgumentException("Duplicate colour");
		set.add(p.colour());
		}
	}

	private void checkTickets() {
		for(ScotlandYardPlayer l : this.listPlayers){
			int i = 0;
			for ( Ticket key : l.tickets().keySet() ){
				if (l.colour() != BLACK) {
					if ( (key == DOUBLE || key == SECRET) && ( l.tickets().get(key) != 0 ) ) 
						throw new IllegalArgumentException("DOUBLE not zero");
				}
				i++;
			}
			if (i != 5)	throw new IllegalArgumentException("Missing tickets");
		}
	}

	private List<ScotlandYardPlayer> createNewListPlayers(PlayerConfiguration mrX, PlayerConfiguration firstDetective, PlayerConfiguration... restOfTheDetectives){
		List<ScotlandYardPlayer> newList = new ArrayList<>();
		ScotlandYardPlayer misterX =  new ScotlandYardPlayer(mrX.player, mrX.colour, mrX.location, mrX.tickets);
		ScotlandYardPlayer first =  new ScotlandYardPlayer(firstDetective.player, firstDetective.colour, firstDetective.location, firstDetective.tickets);
		requireNonNull(newList.add(misterX)); requireNonNull(newList.add(first));
		int i = 0;
		for (PlayerConfiguration l : restOfTheDetectives){
			ScotlandYardPlayer p = new ScotlandYardPlayer(l.player, l.colour, l.location, l.tickets);
			requireNonNull(newList.add(p));
			i++;
		}
		assert(i<6);
		return newList;	
	}
}