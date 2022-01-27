///MestErseGESinTElliGenCiA,Olah.Alex@stud.u-szeged.hu
import game.engine.utils.Pair;
import game.pm.Ghost;
import game.pm.PMAction;
import game.pm.PMGame;

import java.util.*;

/**
 * A 2019 oszi felevenek Mesterseges intelligencia kurzusara keszult beadando.
 * A fejlesztett agens a Monte Carlo fakeresest hasznalja.
 * A reszletes metodus leirasokhoz lasd a metodusok javadoc-jat.
 * */
public class Agent extends game.pm.strategies.Strategy {

    /**
     * @see <a href="https://dke.maastrichtuniversity.nl/m.winands/documents/CIG2012_paper_106.pdf?fbclid=IwAR24yISZS_XojhThXWtpZkRqZrI-NaDiOUlGOIR9FyvWVp9V_3hBSatyzLo">This paper.</a>
     * */
    private static final double CConst = 1.5;
    /**
     * @see <a href="https://dke.maastrichtuniversity.nl/m.winands/documents/CIG2012_paper_106.pdf?fbclid=IwAR24yISZS_XojhThXWtpZkRqZrI-NaDiOUlGOIR9FyvWVp9V_3hBSatyzLo">This paper.</a>
     * */
    private static final int MC_ITERATION_COUNT = 55;
    private Node root;
    private Node currentNode;
    private List<Pair<Integer,Integer>>  availableFood = new ArrayList<>();
    private List<Pair<Integer, Integer>> availableEnergizer = new ArrayList<>();
    private List<Pair<Integer, Integer>> frightenedGhosts = new ArrayList<>();

    private Pair<Integer, Integer> lastPosition;
    private int lastDirection = -1;

    /**
     * A keretrendszer altal nyujtott metodus, mely jelen helyzetben a Monte Carlo fakereses implementaciojanak a magja.
     * Minden irany lekerdezesekor az alabbiak hajtodnak vegre:
     * <ul>
     *     <li>Ha meg nem valtottunk csempet, akkor az elozoleg valasztott irannyal terunk vissza. (processzorido kimelese)</li>
     *     <li>Elinditjuk a Monte Carlo keresest. {@value Agent#MC_ITERATION_COUNT} iteracion keresztul fut.
     *         <ul>
     *             <li>Amig nem erunk levelcsucshoz, megkeressuk a legjobb UCB ertekkel rendelkezo csucsot.</li>
     *             <li>Ha meg nem volt meglatogatva csinalunk egy rolloutot</li>
     *             <li>Ha meg volt latogatva, akkor kibovitjuk gyerekekkel a lehetseges iranyok alapjan. Majd a legelso gyereken vegrehajtunk egy rolloutot.</li>
     *         </ul>
     *     </li>
     * </ul>
     * @param id Pacman azonositoja.
     * @param game A jatek jelenlegi allasa.
     * @return A gyoker legjobb gyerekenek az iranyaval.
     * */
    @Override
    public int getDirection(int id, PMGame game) {

        if(lastPosition != null && lastDirection != -1){
            if(lastPosition.first.equals(game.pacmans[0].getTilePosition().first) && lastPosition.second.equals(game.pacmans[0].getTilePosition().second)){
                return lastDirection;
            }
        }
        lastPosition = new Pair<>(game.pacmans[0].getTilePosition().first, game.pacmans[0].getTilePosition().second);

        currentNode = initTree(game);

        for (Node node:root.children) {
            if(node.pmGame.getLevel() > root.pmGame.getLevel()){
                return node.direction;
            }
        }

        for(int i = 0; i < MC_ITERATION_COUNT; i++) {
            while (!currentNode.isLeaf) {
                currentNode = UCB.findBestNodeWithUCB(currentNode);
            }
            if (currentNode.numberOfVisits == 0) {
                rollout();
            } else {
                currentNode = expand(currentNode);
                rollout();
            }
            currentNode = root;
        }
        Node nodeToReturn =  Collections.max(currentNode.children, Comparator.comparing(c-> c.score));
        lastDirection = nodeToReturn.direction;
        return nodeToReturn.direction;
    }

    /**
     * Megszerzi a jatekban meg elerheto ertekkel rendelkezo targyak helyzeret es kimenti az osztalyvaltozokba.
     * @param game A jatek jelenlegi allasa.
     * */
    private void getAvailableResources(PMGame game) {
        availableFood.clear();
        availableEnergizer.clear();
        frightenedGhosts.clear();
        for(int i = 0; i < game.maze.length; i++){
            for(int j = 0; j < game.maze[0].length; j++){
                if((game.maze[i][j] & PMGame.TILES.FOOD) == PMGame.TILES.FOOD){
                    availableFood.add(new Pair<>(i,j));
                }
                if((game.maze[i][j] & PMGame.TILES.ENERGIZER) == PMGame.TILES.ENERGIZER){
                    availableEnergizer.add(new Pair<>(i,j));
                }
            }
        }
        for (Ghost ghost : game.ghosts) {
            if(ghost.tillFrightened > 0){
                frightenedGhosts.add(new Pair<>(ghost.getTileI(), ghost.getTileJ()));
            }
        }
    }

    /**
     * A Monte Carlo kereses soran hasznalt rollout implementacioja.
     * Minden rollout soran az alabbiak hajtódnak vegre:
     * <ul>
     *     <li>Lekerdezzuk a jelenleg elerheto pont forrasokat.</li>
     *     <li>Kiszamoljuk a legkozelebbiket ezek kozul</li>
     *     <li>Megnezzuk, hol vagyunk. Ez alapjan az alabbiakra szamitunk pontokat.</li>
     *     <ul>
     *         <li>Ha szellemmel talalkozunk</li>
     *         <li>Különben ha ijedt szellemmel talalkozunk</li>ű
     *         <li>Különben ha energizer-rel veszunk fel</li>
     *         <li>Különben ha elelemmel talalkozunk</li>
     *         <li>Különben ha eletet vesztenenk.</li>
     *         <li>Különben a legkozelebbi elelemmel vett tavolsag alapjan adunk pontot.</li>
     *     </ul>
     * </ul>
     * Meghivjuk a {@link #backPropogation(Node, int)} metodust a kapott pont alapjan.
     * */
    private void rollout() {

        getAvailableResources(currentNode.pmGame);

        Pair<Integer, Integer> current = new Pair<>(currentNode.pmGame.pacmans[0].getTileI(), currentNode.pmGame.pacmans[0].getTileJ());
        Pair<Integer, Integer> closestFood = null;
        Pair<Integer, Integer> closestEnergizer = null;
        Pair<Integer, Integer> closestGhost = null;

        if(!availableFood.isEmpty()){
            closestFood = Collections.min(availableFood, Comparator.comparing(c -> distance(current, c)));
        }
        if(!availableEnergizer.isEmpty()){
            closestEnergizer = Collections.min(availableEnergizer, Comparator.comparing(c -> distance(current, c)));
        }
        if(!frightenedGhosts.isEmpty()){
            closestGhost = Collections.min(frightenedGhosts, Comparator.comparing(c -> distance(current, c)));
        }

        int score = 0;
        int currentTile = currentNode.pmGame.getTileAt(currentNode.pmGame.pacmans[0].getTileI(), currentNode.pmGame.pacmans[0].getTileJ());

        if((currentTile & PMGame.TILES.BLINKY) == PMGame.TILES.BLINKY ||
           (currentTile & PMGame.TILES.PINKY) == PMGame.TILES.PINKY ||
           (currentTile & PMGame.TILES.INKY) == PMGame.TILES.INKY ||
           (currentTile & PMGame.TILES.CLYDE) == PMGame.TILES.CLYDE){
            score -= 20000;
        }
        else if((currentTile & PMGame.TILES.FRIGHT_BLINKY) == PMGame.TILES.FRIGHT_BLINKY ||
                (currentTile & PMGame.TILES.FRIGHT_PINKY) == PMGame.TILES.FRIGHT_PINKY ||
                (currentTile & PMGame.TILES.FRIGHT_INKY) == PMGame.TILES.FRIGHT_INKY ||
                (currentTile & PMGame.TILES.FRIGHT_CLYDE) == PMGame.TILES.FRIGHT_CLYDE){
            score += calculateRolloutValues(closestGhost, current, 100000);
        }
        else if((currentTile & PMGame.TILES.ENERGIZER) == PMGame.TILES.ENERGIZER){
            score += calculateRolloutValues(closestEnergizer, current, 2000);
        }
        else if((currentTile & PMGame.TILES.FOOD) == PMGame.TILES.FOOD){
            score += calculateRolloutValues(closestFood, current, 2000);
        }
        else if(currentNode.pmGame.getLives() < currentNode.parent.pmGame.getLives()){
            score -= 30000;
        }
        else{
            score -= (distance(current, closestFood));
        }

        backPropogation(currentNode, score);
    }

    /**
     * Kodduplikacio elkerulese vegett letrehozott metodus. Kiszamol a legkozelebbi erteket hordozo objektumtol vett tavolsag alapjan egy szamot,
     * a szelessegi kereses eredmenye alapjan.
     * @param target A keresett objektumot reprezentalo csempe.
     * @param current A jelenlegi objektumot reprezentalo csempe.
     * @param base Egy konstans szam, amibol kiindulunk.
     * @return Egy szamot, ami azt mondja meg milyen jo az adott lepes.
     * */
    private int calculateRolloutValues(Pair<Integer, Integer> target, Pair<Integer, Integer> current, int base){
        return base - (distanceBfs(current, target )*4);
    }


    /**
     * A szelessegi keresesem implementacioja.
     * @param source Kiindulasi csempe.
     * @param target Cel csempe.
     * @return A kettejuk kozotti tavolsaggal, sajatos modszerrel szamolva.
     * */
    private int distanceBfs(Pair<Integer, Integer> source, Pair<Integer, Integer> target){
        int distance = 0;
        LinkedList<BfsNode> queue = new LinkedList<>();
        BfsNode node = new BfsNode(source);
        node.distance = 0;
        queue.add(node);
        while (queue.size() != 0){
            node = queue.poll();
            distance++;
            for (BfsNode neighbour:node.getAdjacent()) {
                neighbour.distance = distance;
                if(neighbour.tile.first.equals(target.first) && neighbour.tile.second.equals(target.second)){
                    return neighbour.distance;
                }
                queue.add(neighbour);
            }
        }
        return distance;
    }

    /**
     * Egy csucs osztalyreprezentacioja a szelessegi keresesem implementaciojahoz.
     * @author Olah Alex
     * */
    private class BfsNode{
        int distance;
        Pair<Integer, Integer> tile;

        BfsNode(Pair<Integer, Integer> tile){
            this.tile = tile;
        }

        /**
         * Megszerzi ezen csucsnak a szomszedos csucsait.
         * @return Egy lista, amely tartalmazza a szomszedos csucsokat.
         * */
        List<BfsNode> getAdjacent(){
            List<BfsNode> list = new ArrayList<>();
            for(int direction = 0; direction < 4; direction++){
                Pair<Integer,Integer> nextTile = takeAStep(tile.first, tile.second, direction);
                if (PMGame.allowedTile(root.pmGame.getTileAt(nextTile.first, nextTile.second))){
                    list.add(new BfsNode(nextTile));
                }
            }
            return list;
        }
    }

    /**
     * A Monte Carlo fakareses egy epitoeleme. Visszavezeti a fa gyokereig a rollout soran kapott erteket.
     * @param leaf A csucs, ahonnan a rollout indult.
     * @param score Amennyi erteket osszeszedtunk a rollout soran
     * @author Olah Alex
     * */
    private void backPropogation(Node leaf, int score) {
        Node tempNode = leaf;
        while (tempNode != null) {
            tempNode.numberOfVisits++;
            tempNode.score += score;
            tempNode = tempNode.parent;
        }
    }

    /**
     * Letrehozza a kezdeti fat a Monte Carlo kereseshez. Alapbol letrehozza a gyoker gyerekeit is az alapjan, hogy melyik iranyokba lephet.
     * @param pmGame A jatek kezdeti allasa.
     * @return Egy csucs, ami a gyokere lesz a fanak.
     * @author Olah Alex
     * */
    private Node initTree(PMGame pmGame){
        root = new Node(pmGame, null, -1);
        expand(root);
        root.numberOfVisits++;
        return root;
    }

    /**
     * Kibovit egy csucsot annyi gyerekkel, amennyi iranyba tudunk menni.
     * @param node A csucs melyet bovitunk.
     * @return A bovites soran kapott elso gyerek.
     * @author Olah Alex
     * */
    private Node expand(Node node){
        for (int direction = 0; direction < 4; direction++) {
            //lekerdezi hol allna pacman, csak azert, hogy lehet-e olyan lepest tenni
            Pair<Integer,Integer> currentTile = takeAStep(node.pmGame.pacmans[0].getTileI(), node.pmGame.pacmans[0].getTileJ(), direction);
            if (PMGame.allowedTile(node.pmGame.getTileAt(currentTile.first, currentTile.second))) {
                if (node.direction == -1 || (node.direction + 2) % 4 != direction) {
                    PMGame newGameState = node.pmGame.clone();
                    //egy lepest tesz
                    for (int j = 0; j < PMGame.TILES.SIZE / node.pmGame.pacmans[0].getSpeed() + 1; j++) {
                        newGameState.setAction(newGameState.pacmans[0], new PMAction(direction), 0);

                        // valamiert neha ottmaradt, csak egy hibakezeles
                        if ((newGameState.pacmans[0].getTileI() == currentTile.first) &&
                            (newGameState.pacmans[0].getTileJ() == currentTile.second))
                            break;
                    }
                    Node newNode = new Node(newGameState, node, direction);
                    node.addChild(newNode);
                }
            }
        }
        return node.children.get(0);
    }

    /**
     * Megtesz egy lepest az adott iranyba.
     * @param i Jelenlegi csempe elso koordinataja.
     * @param j Jelenlegi csempe masodik koordinataja.
     * @param dir Az irany amibe megyunk.
     * @return A csempe ahova leptunk, ha az irany valid, kulonben null.
     * @author Olah Alex
     * */
    private Pair<Integer, Integer> takeAStep(int i, int j, int dir){
        switch (dir){
            case PMGame.DIRECTION.UP:
                return new Pair<>(--i,j);
            case PMGame.DIRECTION.LEFT:
                return new Pair<>(i,--j);
            case PMGame.DIRECTION.DOWN:
                return new Pair<>(++i,j);
            case PMGame.DIRECTION.RIGHT:
                return new Pair<>(i,++j);
        }
        return null;
    }

    /**
     * Monte Carlo fakeresese soran hasznalt csucsokat reprezentalo osztaly.
     * @author Olah Alex
     * */
    private class Node implements Comparable<Node> {
        Node parent;
        int numberOfVisits;
        double score;
        boolean isLeaf;
        List<Node> children;
        PMGame pmGame;
        int direction;

        Node(PMGame pmGame,
                    Node parent,
                    int direction){
            this.parent = parent;
            numberOfVisits = 0;
            score = 0;
            isLeaf = true;
            children = null;
            this.direction = direction;
            this.pmGame = pmGame;
        }

        /**
         * Hozzaad egy gyereket.
         * @param newNode gyerek csucs.*/
        void addChild(Node newNode){
            if(children == null){
                children = new ArrayList<>();
            }
            children.add(newNode);
            isLeaf = false;
        }

        //comprable implementacioja
        @Override
        public int compareTo(Node o) {
            return Double.compare(this.score, o.score);
        }
    }

    /**
     * Az UCB ertekek kiszamitasahoz hasznalt osztaly.
     * */
    private static class UCB{

        /**Kiszamolja egy csucsnak az UCB erteket.
         * @param totalVisits Szulo latogatottsaganak szama.
         * @param score Csucs erteke.
         * @param numberOfVisits Hanyszor volt a csucs meglatogatva.
         * @return Egy szam, mely az UCB erteket reprezentalja.
         * */
        static double calculateUcb(int totalVisits, double score, int numberOfVisits){
            if(numberOfVisits == 0) return Double.MAX_VALUE;
            return score/numberOfVisits + CConst * Math.sqrt(Math.log(totalVisits/numberOfVisits));
        }

        /**Visszaadja egy csucs gyerekei kozul a legjobb UCB ertekkel rendelkezot.
         * @param node A csucs, mely gyerekei kozul valasztunk.
         * @return A legjobb UCB ertekkel rendelkezo csucs.
         * */
        static Node findBestNodeWithUCB(Node node) {
            return Collections.max(
                    node.children,
                    Comparator.comparing(c -> calculateUcb(node.numberOfVisits, c.score, c.numberOfVisits)));
        }
    }
}