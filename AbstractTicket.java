package com.wclc.gametool.game.ticket;

import static com.wclc.gametool.utils.Print.autoFormat;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.StringJoiner;
import org.apache.commons.csv.CSVRecord;
import com.wclc.drawDateService.drawDateService.DrawDay;
import com.wclc.gametool.game.calendar.DrawCalendar;
import com.wclc.gametool.game.config.GameConfig;
import com.wclc.gametool.game.config.GameConfigs;
import com.wclc.gametool.game.draw.prize.PrizeFilter;
import com.wclc.gametool.game.draw.prize.PrizeInfo;
import com.wclc.gametool.game.draw.prize.PrizeSelector;
import com.wclc.gametool.game.extra.IExtraTicket;
import com.wclc.gametool.game.ticket.controlnumber.ControlNumberException;
import com.wclc.gametool.game.ticket.controlnumber.OnlineControlNumber;
import com.wclc.gametool.game.ticket.preview.TextDocument;
import com.wclc.gametool.game.ticket.preview.TextSection;
import com.wclc.gametool.retailer.RetailerType;
import com.wclc.gametool.tmrepimport.Cancellation;
import com.wclc.gametool.tmrepimport.Validation;
import com.wclc.gametool.utils.RegExUtils;
import com.wclc.gametool.utils.SqlUtils;

import lombok.Getter;
import lombok.Setter;

public abstract class AbstractTicket extends AbstractTransaction implements ITicket {
	@Getter
	@Setter
	private GameConfig game;
	protected List<IGameBoard> boards = new ArrayList<IGameBoard>();
	@Getter
	private int firstDrawNumber;
	@Getter
	private int numberOfDraws;
	@Getter
	private int lastDrawNumber;
	@Getter
	private boolean cancelled;
	@Getter
	private boolean validated;
	private Cancellation cancellation;
	private List<Validation> validations = new ArrayList<Validation>();
	@Getter
	private double ticketAmount;
	@Getter
	protected double wager;
	@Getter
	private String pack;
	@Getter
	@Setter
	protected String bnaTransactionId;
	protected StringJoiner comment = new StringJoiner(" ");
	@Getter
	@Setter
	private String playType = "standard";
	@Getter
	private PrizeInfo prizeInfo;

	private final String detailsRX = "(\\d{6})-(\\d{6})\\s+?(\\d{3})\\s+?(\\d{2}-\\d{4}-\\d{7})\\s+?/\\s+?\\d{2}-\\d{4}-\\d{2}-\\d{8}";

	public AbstractTicket() {
		DrawCalendar.getInstance();
	}

	public AbstractTicket(CSVRecord csvRecord) throws Exception {
		super(csvRecord);
		GameConfigs.getInstance();
		game = GameConfigs.getGameConfig(csvRecord.get("game"));

		int productId = Integer.parseInt(csvRecord.get("product_id"));
		assert (productId == getGame().getNumber()) : String.format("productId (%d) != getGame().getNumber() (%d)", productId, getGame().getNumber());

		pack = csvRecord.get("options");

		// parsing amount_text into 2 float values
		String amountText = csvRecord.get("amount_text");
		String[] amountSplit = amountText.split("[\\(\\)]");
		// "FREE" is a special case
		if (amountSplit[0].equals("FREE"))
			ticketAmount = 0d;
		else
			try {
				ticketAmount = Double.parseDouble(amountSplit[0].trim());
				wager = Double.parseDouble(amountSplit[1].trim());
			} catch (java.lang.NumberFormatException e) {
				System.out.println(amountText);
			}

		String details = csvRecord.get("details");

		List<String> split = RegExUtils.getRegExMatches(details, detailsRX);
		if (split == null || split.size() != 5)
			throw new Exception("Invalid SELL record details: \n" + details);
		firstDrawNumber = Integer.parseInt(split.get(1));
		numberOfDraws = Integer.parseInt(split.get(3).replace("-", ""));

		lastDrawNumber = Integer.parseInt(split.get(2));

		if (firstDrawNumber + numberOfDraws - 1 != lastDrawNumber)
			System.err.println(String.format(
					"%nIncorrect last draw number, possibly, due to excluded draws. Investigation required. Ticket: %d, %d, %s (%d): %d + %d - 1 != %d",
					ticketKeyString, retailer.getNumber(), getDate(), getCdcDay(), firstDrawNumber, numberOfDraws, lastDrawNumber));
		setControlNumber(new OnlineControlNumber(split.get(4)));
	}

	public AbstractTicket(ResultSet rs) throws Exception {
		super(rs);
		GameConfigs.getInstance();
		game = GameConfigs.getGameConfig(rs.getString("game"));
		firstDrawNumber = rs.getInt("first_draw");
		numberOfDraws = rs.getInt("number_of_draws");
		lastDrawNumber = rs.getInt("last_draw");
		ticketAmount = rs.getInt("ticket_amount");
		pack = rs.getString("pack");
		bnaTransactionId = rs.getString("transaction_id");
		cancelled = rs.getBoolean("cancelled");
		validated = rs.getBoolean("validated");
		wager = rs.getDouble("board_amount");
		try {
			getControlNumber().addTag(rs.getString("check_digit"), rs.getString("serial_number"));
		} catch (ControlNumberException e) {
			// e.printStackTrace();
		}

		addGameInfo();
		addPrizeInfo();
	}

	@Override
	public boolean saveToDatabase() {
		StringJoiner query = new StringJoiner(" ", "INSERT INTO ticket ", " ON CONFLICT DO NOTHING;");

		query.add(
				"(date, time, retailer_loc_no, period_no, day_no, product_id, ticket_key_string, game, pack, cdc, ticket_amount, board_amount, first_draw, last_draw, number_of_draws, control_number, check_digit, serial_number)");

		Object[] values = { date, time, getRetailer().getNumber(), getSgDay(), getSgDay(), getGame().getNumber(), ticketKeyString, getGame().getName(), pack,
				getCdcDay(), ticketAmount, wager, firstDrawNumber, firstDrawNumber + numberOfDraws - 1, numberOfDraws, getControlNumber().toSqlControlNumber(),
				getControlNumber().getCheckDigit(), getControlNumber().getSerial() };
		StringJoiner valuesList = new StringJoiner(", ", "VALUES (", ")");
		for (Object value : values)
			valuesList.add(String.format("'%s'", value.toString()));

		query.add(valuesList.toString());
		try {
			return (SqlUtils.executeUpdate(query.toString()) > 0);
		} catch (SQLException e) {
			e.printStackTrace();
		}
		return false;
	}

	private void addPrizeInfo() throws Exception {
		PrizeFilter prizeFilter = new PrizeFilter();
		prizeFilter.addIncludeTicketKeyString(ticketKeyString);
		prizeInfo = PrizeSelector.select(prizeFilter);
	}

	public int size() {
		int result = 0;
		for (IGameBoard board : this)
			result += board.size();
		return result;
	}

	@Override
	public final Iterator<IGameBoard> iterator() {
		return this.boards.iterator();
	}

	public final IGameBoard getBoard(int index) {
		return boards.get(index);
	}

	public abstract void add(List<Integer> selection, Boolean isExtraEntered, Boolean quickPick) throws Exception;

	public boolean isFreePlay() {
		return this.playType.equalsIgnoreCase("free play");
	}

	protected ResultSet getGameRecordFromDatabase() throws Exception {
		String selectionsQuery = String.format("SELECT * FROM \"%s\" WHERE ticket_key_string = '%s';", this.getGame().getName().toLowerCase(),
				this.getTicketKeyString());
		ResultSet selectionRs = SqlUtils.executeQuery(selectionsQuery);
		return selectionRs;
	}

	protected ResultSet getSelectionsFromDatabase() throws Exception {
		String selectionsQuery = autoFormat("SELECT * FROM \"%s_selection\" WHERE ticket_key_string = '%?' ORDER BY selection_number;",
				this.getGame().getName().toLowerCase(), this.getTicketKeyString());
		ResultSet selectionRs = SqlUtils.executeQuery(selectionsQuery);
		return selectionRs;
	}

	protected abstract void addGameInfo() throws Exception;

	/**
	 * Used specifically for reporting, to account for free plays' wagers
	 * 
	 * @return
	 */
	public double getCarrierNominalWager() {
		Double result = 0d;

		for (IGameBoard board : this)
			result += board.getCost();
		return result;
	}

	public double getCarrierNominalCost() {
		return this.getCarrierNominalWager() * this.getNumberOfDraws();
	}

	public double getCarrierWager() {
		if (this.isFreePlay())
			return 0d;
		else
			return getCarrierNominalWager();
	}

	public double getCarrierCost() {
		return this.getCarrierWager() * this.getNumberOfDraws();
	}

	public double getRiderCost() {
		return getRiderWager() * getNumberOfDraws();
	}

	public double getRiderWager() {
		double result = 0f;
		if (this instanceof IExtraTicket)
			result = ((IExtraTicket) this).getExtra().getWager();
		return result;
	}

	public int getSelectionCount() {
		int result = 0;
		for (IGameBoard board : this)
			result += board.getSelectionCount();
		return result;
	}

	public int getRiderSize() {
		int result = 0;
		if (this instanceof IExtraTicket)
			result = ((IExtraTicket) this).getExtra().size();
		return result;
	}

	public double getTicketCost() {
		Double result = this.getCarrierCost() + this.getRiderCost();
		// Don't verify for FP, since FP have different notation for regular retailers
		// and subscription
		// if (!this.isFreePlay())
		// if (result.intValue() != ticketAmount)
		// throw new RuntimeException(
		// String.format("Error: calculated ticket cost (%.2f) differs from TMREP cost
		// (%d.00) [%s %s]",
		// result, ticketAmount, this.getGame(), this.getControlNumber()));
		return result;
	}

	public String getDisplayString(TicketView ticketView) throws Exception {
		switch (ticketView) {
		case CANCELATION:
			if (this.isCancelled())
				return getCancellation().toString().replace("{TICKET_NUMBER}", getControlNumber().toString()).replace("{GAME}", game.getDescription());
		case COMMENT:
			return getComment();
		case TICKET:
			return toString();
		case VALIDATION:
			if (this.isValidated()) {
				StringJoiner result = new StringJoiner("\n\n==============================\n\n");
				for (Validation validation : getValidations()) {
					String validationString = validation.toString().replace("{TICKET_NUMBER}", getControlNumber().toString()).replace("{GAME}",
							game.getDescription());
					result.add(validationString);
				}
				return result.toString();
			}
		case PRIZES:
			if (prizeInfo != null && !prizeInfo.isEmpty())
				return getPrizeInfo().toString();
		default:
			return null;
		}
	}

	public DrawDay getFirstDrawDay() {
		return DrawCalendar.fromDrawNumber(getGame(), firstDrawNumber).getDraws().get(0);
	}

	public DrawDay getLastDrawDay() {
		return DrawCalendar.fromDrawNumber(getGame(), getLastDrawNumber()).getDraws().get(0);
	}

	public TextDocument getTextDocument() {
		TextDocument document = new TextDocument();
		TextSection header = new TextSection(30);
		header.appendText(getHeader());
		TextSection content = new TextSection(30);
		for (IGameBoard board : this)
			content.appendText(board.toString());
		TextSection footer = new TextSection(30);
		footer.appendText(getFooter());
		document.setHeader(header);
		document.setContent(content);
		document.setFooter(footer);
		return document;
	}

	@Override
	public String toString() {
		return getTextDocument().toString();
	}

	public boolean isQuickPick() {
		for (IGameBoard board : this)
			if (board.isQuickPick())
				return true;
		return false;
	}

	private String getHeader() {
		StringJoiner result = new StringJoiner("\n");
		result.add("==============================");
		result.add(getGame().getDescription().toUpperCase());
		result.add("");
		result.add(getNumberOfDraws() + (getNumberOfDraws() > 1 ? " DRAWS" : " DRAW"));
		return result.toString();
	}

	private String getFooter() {
		StringJoiner result = new StringJoiner("\n");
		result.add("SEE REVERSE");
		if (this.getTicketCost() == 0f)
			result.add("FREE PLAY");
		else
			result.add("$  " + this.getTicketCost() + "0");
		result.add("SYSID " + this.retailer.toString());
		result.add("");
		result.add("X_____________________________");
		result.add("PRINT YOUR NAME HERE");
		result.add("");
		result.add("TICKET TAG");
		return result.toString();
	}

	/**
	 * Detects all categories of wins from the selections on the ticket
	 * 
	 * @throws Exception
	 */

	public void addComment(String comment) {
		this.comment.add(comment);
	}

	public String getComment() {
		return this.comment.toString();
	}

	public Cancellation getCancellation() {
		if (this.isCancelled() && this.cancellation == null)
			cancellation = Cancellation.fromDatabase(ticketKeyString);
		return cancellation;
	}

	public List<Validation> getValidations() {
		if (this.isValidated() && (this.validations == null || this.validations.isEmpty()))
			validations = Validation.fromDatabase(ticketKeyString);
		return validations;
	}

	public Object getValue(String name) {
		Field field;
		Method method;

		if (name.endsWith("()")) {
			try {
				method = this.getClass().getMethod(name.replace("()", ""));
				return method.invoke(this);
			} catch (SecurityException | NoSuchMethodException | IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
				e.printStackTrace();
			}
		} else {
			try {
				field = ITicket.class.getDeclaredField(name);
				return field.get(this);
			} catch (NoSuchFieldException | SecurityException | IllegalArgumentException | IllegalAccessException e) {
				e.printStackTrace();
			}
		}
		return null;
	}

	public RetailerType getRetailerType() {
		return retailer.getType();
	}

}
