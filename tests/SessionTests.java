// SPDX-License-Identifier: GPL-3.0-only
package ru.x96.local;
import static ru.x96.local.CoreTests.*;

public final class SessionTests {
    public static void main(String[] args) throws Exception {
        Object first=new Object(),second=new Object();SessionAction action=new SessionAction(first,1);
        check(action.matches(first,1),"original connection matches");
        check(!action.take(second,1,false,false),"same epoch cannot authorize different connection");
        check(!action.take(first,2,false,false),"reconnect invalidates old confirmation even with same object");
        check(!action.take(null,1,false,false),"disconnected confirmation rejected");
        check(!action.take(first,1,true,false),"busy operation not started twice");
        check(!action.take(first,1,false,true),"destroyed screen cannot act");
        check(action.take(first,1,false,false),"valid confirmation allowed once");
        check(!action.take(first,1,false,false)&&!action.matches(first,1),"duplicate callback cannot repeat command");
        rejects(()->new SessionAction(null,1));
        int[] mutations={0};SessionAction oldPicker=new SessionAction(first,7);
        if(oldPicker.take(second,8,false,false))mutations[0]++;
        check(mutations[0]==0,"file selected for old TV is never applied to new TV");
        SessionAction freshPicker=new SessionAction(second,8);
        if(freshPicker.take(second,8,false,false))mutations[0]++;
        if(freshPicker.take(second,8,false,false))mutations[0]++;
        check(mutations[0]==1,"fresh picker result handled exactly once");
        SessionAction menu=new SessionAction(first,10);check(menu.matches(first,10),"menu may open confirmation without consuming authorization");
        check(!menu.take(second,11,false,false),"nested confirmation rejects session switch");
        System.out.println("SessionTests: "+checks+" checks passed");
    }
}
