package jtransc.rt.test;

public class JTranscStringTest {
	static public void main(String[] args) {
		Comparable<String> a = "a";
		System.out.println("a".equals("a"));
		System.out.println("a".equals("b"));

        System.out.println("abcdef".equals("abcdef"));
        System.out.println("abcdef".equals("abcde_"));
        System.out.println("abcdef".equals("_bcdef"));

        System.out.println("abcdef".hashCode());

		System.out.println("a".toUpperCase());
		System.out.println("a".compareTo("b"));
		System.out.println("a".compareTo("a"));
		System.out.println("b".compareTo("a"));
		System.out.println(a.compareTo("b"));

		//try {
		//	((Test0<Integer>) null).getTest();
		//} catch (Throwable t) {
		//}
	}

}