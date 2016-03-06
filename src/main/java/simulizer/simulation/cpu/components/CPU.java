package simulizer.simulation.cpu.components;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BrokenBarrierException;

import simulizer.assembler.representation.Address;
import simulizer.assembler.representation.Annotation;
import simulizer.assembler.representation.Instruction;
import simulizer.assembler.representation.Label;
import simulizer.assembler.representation.Program;
import simulizer.assembler.representation.Register;
import simulizer.assembler.representation.Statement;
import simulizer.assembler.representation.operand.Operand;
import simulizer.simulation.cpu.user_interaction.IO;
import simulizer.simulation.data.representation.DataConverter;
import simulizer.simulation.data.representation.Word;
import simulizer.simulation.exceptions.DecodeException;
import simulizer.simulation.exceptions.ExecuteException;
import simulizer.simulation.exceptions.HeapException;
import simulizer.simulation.exceptions.InstructionException;
import simulizer.simulation.exceptions.MemoryException;
import simulizer.simulation.exceptions.ProgramException;
import simulizer.simulation.exceptions.StackException;
import simulizer.simulation.instructions.InstructionFormat;
import simulizer.simulation.listeners.*;
import simulizer.simulation.listeners.StageEnterMessage.Stage;

/**this is the central CPU class
 * this is how the following components fit into this class
 *  - Control Unit - implicit in this class
 *  - Program Counter - Address Object
 *  - Instruction Register - Statement object
 *  - ALU - External object
 *  - L/S Unit - Not required (will still be shown in visualisation)
 *  - Registers - array of 4 byte words
 *  - Main Memory - External Memory Object
 * @author Charlie Street
 *
 */
public class CPU {

    private List<SimulationListener> listeners;
    private ListenerController listenControl;

    protected Address programCounter;
    protected Statement instructionRegister;

    private ALU Alu;
    protected Clock clock;//clock cycle for IE cycle

    private Word[] registers;
    private MainMemory memory;
    
    private Decoder decoder;
    private Executor executor;

    private Program program;//all information on how to run the program
    public Map<String, Address> labels;
    private Map<String, Label> labelMetaData;

	protected Map<Address, Annotation> annotations;

    protected boolean isRunning;//for program status
    protected Address lastAddress;//used to determine end of program
    
    private IO io;


    /**the constructor will set all the components up
     *
     * @param io the io type being used
     */
    public CPU(IO io) {
        listeners = new ArrayList<>();
        this.listenControl = new ListenerController(listeners);
        this.clearRegisters();
        this.clock = new Clock(100);
        this.isRunning = false;
        this.io = io;
        this.decoder = new Decoder(this);
        this.executor = new Executor(this);
    }

    /**this method will set the clock controlling
     * the execution cycle to run
     */
    public void startClock() {
        clock.reset();
        clock.startRunning();
        if(!clock.isAlive()) {
            clock.start();//start the clock thread
        }
    }

    /**this method will set the clock controlling
     * the execution cycle to run
     */
    public void pauseClock() {
        if(clock != null) {
            clock.stopRunning();
            io.cancelRead();
        }
    }

    /**sets the speed in ms of the clock
     * will be 3 times what it says to make it representative wrt. the pipeline
     * @param tickMillis the time for one clock cycle
     */
    public void setClockSpeed(int tickMillis) {
        clock.tickMillis = 3 * tickMillis;
    }

    /**gets the speed in ms of the clock
     *
     */
    public int getClockSpeed(){ return clock.tickMillis; }

    /**stop the running of a program
     *
     */
    public void stopRunning() {
        // harmless to call multiple times, only want to send one message
        boolean wasRunning = isRunning;

        isRunning = false;
        if(clock != null) {
            pauseClock();
            try {
                clock.join(); // stop the clock thread
            } catch(InterruptedException e) {
                e.printStackTrace();
            }
        }

        if(wasRunning) {
            sendMessage(new SimulationMessage(SimulationMessage.Detail.SIMULATION_STOPPED));
        }
    }

    /**
     * Register a listener to receive messages
     * wrapper for method in listener, makes more sense as wrapper for connecting to CPU
     * @param l the listener to send messages to
     */
    public void registerListener(SimulationListener l) {
    	this.listenControl.registerListener(l);
    }

    /**
     * Unregisters a listener from the list
     * wrapper for method in listener, things listen to the CPU
     * @param l the listener to be removed
     */
    public void unregisterListener(SimulationListener l){
        this.listenControl.unregisterListener(l);
    }

    /**
     * send a message to all of the registered listeners
     * wrapper for method in listener, things listen and are sent from the CPU
     * @param m the message to send
     */
    protected void sendMessage(Message m) {
        this.listenControl.sendMessage(m);
    }

    /**this method is used to set up the cpu whenever a new program is loaded into it
     *
     * @param program the program received from the assembler
     */
    public void loadProgram(Program program) {
        this.program = program;
        this.clock = new Clock(100);//re-initialise clock
        this.instructionRegister = null;//nothing to put in yet so null

        this.clearRegisters();//reset the registers

        //setting up memory
        Address textSegmentStart = this.program.textSegmentStart;
        Address dataSegmentStart = this.program.dataSegmentStart;
        Address dynamicSegmentStart = this.program.dynamicSegmentStart;
        Address stackPointer = new Address((int)DataConverter.decodeAsSigned(this.program.initialSP.getWord()));
        byte[] staticDataSegment = this.program.dataSegment;
        Map<Address,Statement> textSegment = this.program.textSegment;
        this.memory = new MainMemory(textSegment,staticDataSegment,textSegmentStart,dataSegmentStart,dynamicSegmentStart,stackPointer);

        labels = new HashMap<>();
        labelMetaData = new HashMap<>();
        for(Map.Entry<Label, Address> l : program.labels.entrySet()) {
            String key = l.getKey().getName();
            labels.put(key, l.getValue());
            labelMetaData.put(key, l.getKey());
        }

		annotations = program.annotations;

        try {
            this.programCounter = getEntryPoint();//set the program counter to the entry point to the program
        } catch(Exception e) {//if entry point load fails
            sendMessage(new ProblemMessage(e.getMessage()));//send problem to logger
        }

        this.registers[Register.gp.getID()] = this.program.initialGP;//setting global pointer
        sendMessage(new RegisterChangedMessage(Register.gp));
        this.registers[Register.sp.getID()] = this.program.initialSP;//setting up stack pointer
        sendMessage(new RegisterChangedMessage(Register.gp));

        this.Alu = new ALU();//initialising Alu
        this.lastAddress = program.textSegmentLast;
    }


    /**this method resets the registers in the memory
     * it then initialises them to some default value
     */
    private void clearRegisters() {
        this.registers = new Word[32];
        for(int i = 0; i < this.registers.length; i++) {
            this.registers[i] = new Word(DataConverter.encodeAsUnsigned(0));
            sendMessage(new RegisterChangedMessage(Register.fromID(i)));//firing to visualisation
        }
    }

    /**this method will look for the main label and get it's corresponding address
     * this is for use with the program counter
     * @throws ProgramException thrown if no main label is found
     */
    private Address getEntryPoint() throws ProgramException {
        Map<Label,Address> labels = this.program.labels;//map of labels

        for(Map.Entry<Label, Address> entry : labels.entrySet()) {//iterating through map
            if(entry.getKey().getName().toLowerCase().equals("main")) {//if main label found
                return entry.getValue();
            }
        }

        throw new ProgramException("No main label found.", this.program);
    }

    /**carries out the fetch part of the FDE cycle (non pipelined)
     *
     */
    protected void fetch() throws MemoryException {
    	sendMessage(new StageEnterMessage(Stage.Fetch));//signal start of stage
        this.instructionRegister = this.memory.readFromTextSegment(this.programCounter);
        sendMessage(new DataMovementMessage(Optional.empty(), Optional.of(this.instructionRegister)));
        this.programCounter = new Address(this.programCounter.getValue() + 4);//incrementing the program counter
    }

    /**method decodes within the cpu
     * wrapper from decoder class but necessary for a nice inheritance structure
     * @param instruction the instruction format to decode
     * @param operandList the list of operands to be decoded
     * @return InstructionFormat the instruction ready for execution
     * @throws DecodeException if something goes wrong during decode
     */
    protected InstructionFormat decode(Instruction instruction, List<Operand> operandList) throws DecodeException {
    	return this.decoder.decode(instruction,operandList);
    }

    /**this method will execute the instruction given to it
     * wrapper for method in Executor, gives nice inheritance layout
     * @param instruction instruction set up with all necessary data
     * @throws Exception thrown from execute in executor, refer to that method for more precise infro
     */
    protected void execute(InstructionFormat instruction) throws InstructionException, ExecuteException, MemoryException, HeapException, StackException {
        this.programCounter = this.executor.execute(instruction,this.getProgramCounter());//will set the program counter if changed
    }

    
    /**this method will run a single cycle of the FDE cycle
     * @throws MemoryException if problem accessing memory
     * @throws DecodeException if error during decode
     * @throws InstructionException if error with instruction
     * @throws ExecuteException if problem during execution
     * @throws HeapException if the heap goes wrong at some point
     * @throws StackException 
     *
     */
    public void runSingleCycle() throws MemoryException, DecodeException, InstructionException, ExecuteException, HeapException, StackException {

        // PC holds next instruction and is advanced by fetch,
        // messages should be sent about this instruction instead
        Address thisInstruction = programCounter;

        fetch();
        sendMessage(new ExecuteStatementMessage(thisInstruction));
		InstructionFormat instruction = decode(this.instructionRegister.getInstruction(), this.instructionRegister.getOperandList());
		
		execute(instruction);
		if(annotations.containsKey(thisInstruction)) {
			sendMessage(new AnnotationMessage(annotations.get(thisInstruction), thisInstruction));
		}

        if(this.programCounter.getValue() == this.lastAddress.getValue()+4) {//if end of program reached
        	//clean exit but representing in reality an error would be thrown
            this.isRunning = false;//stop running
            sendMessage(new ProblemMessage("Program tried to execute a program outside the text segment. "
					+ "This could be because you forgot to exit cleanly."
					+ " To exit cleanly please call syscall with code 10."));
        }
    }

    /**this method will run the program given to the CPU, it will operate under the clock cycle
     *
     */
    public void runProgram() {
    	this.startClock();
        this.isRunning = true;
        sendMessage(new SimulationMessage(SimulationMessage.Detail.SIMULATION_STARTED));

        // used for setting up the annotation environment eg loading visualisations
        if(program.initAnnotation != null) {
            sendMessage(new AnnotationMessage(program.initAnnotation, null));
        }

        while(isRunning) { //need something to stop this
        	try {
        		this.runSingleCycle();//run one loop of Fetch,Decode,Execute
        	} catch(MemoryException | DecodeException | InstructionException | ExecuteException | HeapException | StackException e) {
        		sendMessage(new ProblemMessage(e.getMessage()));
        		this.isRunning = false;
        	}
            try {
                if(isRunning) {
                    clock.waitForNextTick();
                }
            } catch(InterruptedException | BrokenBarrierException e) {
                sendMessage(new SimulationMessage(SimulationMessage.Detail.SIMULATION_INTERRUPTED));
            }
		}
        stopRunning();
    }


    //Standard get methods, don't do anything special
	public Word[] getRegisters() {
		return registers;
	}

    public MainMemory getMainMemory() {
        return memory;
    }
 
    public Address getProgramCounter() {
    	return programCounter;
    }
    
    public ALU getALU() {
    	return Alu;
    }
    
    public IO getIO() {
    	return io;
    }
    
    public Program getProgram() {
        return program;
    }
}
