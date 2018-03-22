/* Katherine Haberlin 
   CSCE 311 Project 2
   March 1, 2018 
*/
package osp.Threads;
import java.util.Vector;
import java.util.Enumeration;
import osp.Utilities.*;
import osp.IFLModules.*;
import osp.Tasks.*;
import osp.EventEngine.*;
import osp.Hardware.*;
import osp.Devices.*;
import osp.Memory.*;
import osp.Resources.*;
import java.util.List;
import java.util.Queue;

/**
   This class is responsible for actions related to threads, including
   creating, killing, dispatching, resuming, and suspending threads.
   @OSPProject Threads
*/
public class ThreadCB extends IflThreadCB 
{
    private static GenericList[] activeArray;
    private static GenericList[] expiredArray;
    private static boolean qFlag;

    /**
       The thread constructor. Must call 
           super();
       as its first statement.
       @OSPProject Threads
    */
    public ThreadCB()
    {
      super();
    }

    /**
       This method will be called once at the beginning of the
       simulation. The student can set up static variables here.
       
       @OSPProject Threads
    */
    public static void init()
    {
      /* Initialize the active and expired arrays as a GenericLists */    
      activeArray = new GenericList[5];
      expiredArray = new GenericList[5];
      for(int i = 0; i < 5; i++)
      {
        activeArray[i] = new GenericList();
        expiredArray[i] = new GenericList();
      }

      /* Flags for why a thread was interrupted */
      qFlag = false;
    }

    /** 
        Sets up a new thread and adds it to the given task. 
        The method must set the ready status 
        and attempt to add thread to task. If the latter fails 
        because there are already too many threads in this task, 
        so does this method, otherwise, the thread is appended 
        to the ready queue and dispatch() is called.
    The priority of the thread can be set using the getPriority/setPriority
    methods. However, OSP itself doesn't care what the actual value of
    the priority is. These methods are just provided in case priority
    scheduling is required.
    @return thread or null
        @OSPProject Threads
    */
    public static ThreadCB do_create(TaskCB task)
    {
      ThreadCB newThread = null;

      /* Check if task is null */
      if(task == null)
      {
          ThreadCB.dispatch();
          return null;
      }
       
      /* Check if task has exceeded maximum number of threads */
      if(task.getThreadCount() >= MaxThreadsPerTask)
      {
          ThreadCB.dispatch();
          return null;
      }

      /* Create a thread object */
      newThread = new ThreadCB();

      /* Set thread priority and status */
      newThread.setPriority(2);
      newThread.setStatus(ThreadReady);
      newThread.setTask(task);

      /* Link thread and tasks together */
      if(task.addThread(newThread) == 0)
      {
          ThreadCB.dispatch();
          return null;
      }

      /* Set default priority to 2 and place in expired array */
      expiredArray[2].append(newThread);
      ThreadCB.dispatch();
      return newThread;        
  }

    /** 
    Kills the specified thread. 
    The status must be set to ThreadKill, the thread must be
    removed from the task's list of threads and its pending IORBs
    must be purged from all device queues.
        
    If some thread was on the ready queue, it must removed, if the 
    thread was running, the processor becomes idle, and dispatch() 
    must be called to resume a waiting thread.
    
    @OSPProject Threads
    */
    public void do_kill()
    {     
      /* If status is ThreadReady, remove from readyQueue */
      if(getStatus() == ThreadReady)                                          
      {  
        for (int i = 0; i < 5; i++)
        {
          activeArray[i].remove(this);
          expiredArray[i].remove(this);
        }
      }

      /* If status is ThreadRunning, preempt it */
      else if(getStatus() == ThreadRunning)                                          {
        ThreadCB curThread = null;
        try
        {
          curThread = MMU.getPTBR().getTask().getCurrentThread();
          if(this == curThread)
          {
            MMU.setPTBR(null);
            getTask().setCurrentThread(null);
          }
        }
        catch(NullPointerException e){}
      }
        
      /* Remove the task and change the thread status */
      getTask().removeThread(this);
      setStatus(ThreadKill);
      
      /* Loop through device table to purge IORB */
      int size = Device.getTableSize();
      for(int i = 0; i < size; i++)
      {
        Device.get(i).cancelPendingIO(this);
      }
        
      /* Release resources, dispatch, and kill */
      ResourceCB.giveupResources(this); 
      dispatch();

      if(getTask().getThreadCount() == 0)
      {
        getTask().kill();
      } 
    }

    /** Suspends the thread that is currently on the processor on the 
        specified event. 
        Note that the thread being suspended doesn't need to be
        running. It can also be waiting for completion of a pagefault
        and be suspended on the IORB that is bringing the page in.
    
    Thread's status must be changed to ThreadWaiting or higher,
        the processor set to idle, the thread must be in the right
        waiting queue, and dispatch() must be called to give CPU
        control to some other thread.
    @param event - event on which to suspend this thread.
        @OSPProject Threads
    */
    public void do_suspend(Event event)
    {
      /* Find initial status, or get the getStatus call will change */
      int status = getStatus();

      /* If status is ThreadWaiting, increment it */
      if(status>=ThreadWaiting)
      {
        setStatus(getStatus()+1);
      }

      /* If status is ThreadRunning, suspend it */
      else if(status == ThreadRunning)
      {
        ThreadCB curThread = null;
        try
        {
          curThread = MMU.getPTBR().getTask().getCurrentThread();
          if(this==curThread)
          {
            MMU.setPTBR(null);
            getTask().setCurrentThread(null);
            setStatus(ThreadWaiting); 
          }
        }
        catch(NullPointerException e){}  
      }

      /* If thread is not in readyQueue, add thread to waitingQueue */
      if(!activeArray[getPriority()].contains(this))
      {
        event.addThread(this);
      }
      else
      {
        activeArray[getPriority()].remove(this);
      }
        
      /* Dispatch a thread */
      ThreadCB.dispatch();
    }

    /** Resumes the thread.
        
    Only a thread with the status ThreadWaiting or higher
    can be resumed.  The status must be set to ThreadReady or
    decremented, respectively.
    A ready thread should be placed on the ready queue.
        
    @OSPProject Threads
    */
    public void do_resume()
    {
      /* Taken directly from OSP2 textbook */
  
      if(getStatus() < ThreadWaiting) {
        MyOut.print(this, "Attempt to resume " +  
                    this + ", which wasn't waiting");
        return;
      }
        
      MyOut.print(this, "Resuming " + this);
        
      //Set thread's status
      if (getStatus() == ThreadWaiting) {
          setStatus(ThreadReady);
      } else if (this.getStatus() > ThreadWaiting) {
          setStatus(getStatus()-1);
      }  
      
      //Put the thread on the ready queue, if appropriate
      if (getStatus() == ThreadReady) {     
        /* If thread had been interrupted, send to active array 
           If quantum expired, increase priority and send to active array */
        if (getPriority() != 0) 
        {
          if(qFlag && getPriority() > 0)
          {
            setPriority(getPriority() - 1);
          }
          activeArray[getPriority()].append(this);
        }
      }       
      dispatch();
    }

    /** 
        Selects a thread from the run queue and dispatches it. 
        If there is just one theread ready to run, reschedule the thread 
        currently on the processor.
        In addition to setting the correct thread status it must
        update the PTBR.
    
    @return SUCCESS or FAILURE
        @OSPProject Threads
    */
    public static int do_dispatch()
    {
      ThreadCB curThread = null;
   
      try
      {
        curThread = MMU.getPTBR().getTask().getCurrentThread();
      }
      catch(NullPointerException e) {}

      /* If a thread is running, send to expired array */
      if (curThread != null) 
      {
        /* Check the reason the thread was preempted 
           If time < 1, exceeded quantum
           If time >= 1, interrupted                 */
        long time = HTimer.get();
        if (time < 1 && curThread.getPriority() < 4)
        {
          qFlag = true;
          curThread.setPriority(curThread.getPriority() + 1);
        }
        else
        {
          qFlag = false;
        }
     
        curThread.getTask().setCurrentThread(null);
        MMU.setPTBR(null);
        curThread.setStatus(ThreadReady);
        expiredArray[curThread.getPriority()].append(curThread);
      }

      /* Dispatch next thread */
      curThread = nextThread();

      if (curThread == null)
      { 
        MMU.setPTBR(null);
        return FAILURE;
      }
   
      else
      {
        MMU.setPTBR(curThread.getTask().getPageTable());
        curThread.getTask().setCurrentThread(curThread);
        curThread.setStatus(ThreadRunning);
      }

      /* Set quantum based on priority */
      int prior = curThread.getPriority();
      if (prior == 0 || prior == 1 || prior == 2)
      {
        HTimer.set(40);
      }
      else
      {
        HTimer.set(20);
      }
 
      return SUCCESS;
    }


    /** Returns the next thread from the active array.
        @return ThreadCB nextThread or NULL

        @OSPProject Threads
    */
    public static ThreadCB nextThread()
    {
      /* Search activeArray for first thread available */
      for (int i =0; i < 5; i++)
      {
        if(!activeArray[i].isEmpty())
        {
          return (ThreadCB)activeArray[i].removeHead();
        }
      }

      /* active Array was empty, swap active and expired */
      GenericList[] tempArray = new GenericList[5];
      tempArray = activeArray;
      activeArray = expiredArray;
      expiredArray = tempArray;

      /* Search NEW activeArray for first thread available */
      for (int i = 0; i < 5; i++)
      {
        if(!activeArray[i].isEmpty())
          return (ThreadCB)activeArray[i].removeHead();
      }

      /* Both arrays were empty, return null */
      return null;
    }  

    /**
       Called by OSP after printing an error message. The student can
       insert code here to print various tables and data structures in
       their state just after the error happened.  The body can be
       left empty, if this feature is not used.
       @OSPProject Threads
    */
    public static void atError()
    {

    }

    /** Called by OSP after printing a warning message. The student
        can insert code here to print various tables and data
        structures in their state just after the warning happened.
        The body can be left empty, if this feature is not used.
       
        @OSPProject Threads
     */
    public static void atWarning()
    {
        // your code goes here

    }  
}

/*
      Feel free to add local classes to improve the readability of your code
*/

